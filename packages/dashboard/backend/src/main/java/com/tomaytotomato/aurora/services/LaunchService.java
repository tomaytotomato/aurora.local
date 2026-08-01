package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs {@code scripts/up.sh} on behalf of the operator during onboarding.
 *
 * <p>Iter-1 goal: kill the "SSH into the box" cliff at the end of the wizard.
 * Aurora already has bash + the docker CLI in the runtime image (see
 * {@code Dockerfile}), the repo bind-mounted at {@code /repo}, and access to
 * {@code /var/run/docker.sock}, so it can invoke {@code up.sh} directly.
 *
 * <p>State model:
 * <ul>
 *   <li>{@link ConcurrentHashMap} of jobId → {@link Job}. In-memory only —
 *       a restart nukes the job record, which is fine because the containers
 *       up.sh started keep running.</li>
 *   <li>{@link #activeJobId} enforces one-in-flight globally. Second POST
 *       while a job is running returns 409 CONFLICT via
 *       {@link LaunchInProgressException}.</li>
 *   <li>Per-job SSE emitters live in a {@link CopyOnWriteArrayList} on the
 *       {@link Job}. The stream endpoint attaches to that list; the process
 *       reader thread fans out lines.</li>
 * </ul>
 *
 * <p>Logs are also mirrored to {@code /data/launch-logs/launch-<jobid>.log}
 * (aurora_data volume) for post-mortem.
 */
@Service
public class LaunchService {

  private static final Logger log = LoggerFactory.getLogger(LaunchService.class);

  /** Bounded tail buffer — memory ceiling per job. */
  private static final int TAIL_MAX_LINES = 4096;
  private static final int LOG_DIR_MODE_HINT = 0;
  private static final String LOG_DIR = "/data/launch-logs";

  private final AuroraProperties props;
  private final AuditEventRepo audit;

  private final Map<String, Job> jobs = new ConcurrentHashMap<>();
  private final AtomicReference<String> activeJobId = new AtomicReference<>(null);

  private final ScheduledExecutorService heartbeat =
      Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "aurora-launch-heartbeat");
        t.setDaemon(true);
        return t;
      });

  public LaunchService(AuroraProperties props, AuditEventRepo audit) {
    this.props = props;
    this.audit = audit;
    // Fires every 15s. Cheap; iterates active job's emitters only.
    heartbeat.scheduleAtFixedRate(this::sendHeartbeats, 15, 15, TimeUnit.SECONDS);
  }

  // ------------------------------------------------------------------
  // Public API
  // ------------------------------------------------------------------

  /**
   * Start a launch job. Rejects with {@link LaunchInProgressException} if
   * one is already running.
   *
   * @param enabledPackages ordered list of package names (from .state.yml)
   * @return the created job (state == RUNNING)
   */
  public synchronized Job startLaunch(List<String> enabledPackages) {
    String current = activeJobId.get();
    if (current != null) {
      Job existing = jobs.get(current);
      if (existing != null && existing.state == State.RUNNING) {
        throw new LaunchInProgressException(current);
      }
    }

    String id = UUID.randomUUID().toString();
    Job job = new Job(id, List.copyOf(enabledPackages));
    jobs.put(id, job);
    activeJobId.set(id);

    // Best-effort log file.
    try {
      Files.createDirectories(Path.of(LOG_DIR));
      job.logFile = Path.of(LOG_DIR, "launch-" + id + ".log");
      Files.writeString(job.logFile,
          "# aurora launch " + id + " started " + job.startedAt + "\n"
              + "# packages: " + String.join(",", job.packages) + "\n",
          StandardCharsets.UTF_8);
    } catch (IOException e) {
      log.warn("could not create launch log file at {}: {}", LOG_DIR, e.getMessage());
      job.logFile = null;
    }

    audit.record(null, "onboarding.launch.start", "job:" + id,
        "{\"packages\":" + toJsonArray(job.packages) + "}");

    Thread runner = new Thread(() -> run(job), "aurora-launch-" + id.substring(0, 8));
    runner.setDaemon(true);
    runner.start();
    return job;
  }

  public Job get(String jobId) {
    return jobs.get(jobId);
  }

  /**
   * Attach an SSE emitter to a job. Immediately replays the tail buffer so a
   * late subscriber (or a page reload) sees prior output. If the job has
   * already finished, sends the terminal {@code done} event and completes.
   */
  public void subscribe(String jobId, SseEmitter emitter) {
    Job job = jobs.get(jobId);
    if (job == null) {
      try { emitter.completeWithError(new IllegalArgumentException("no such job")); } catch (Exception ignore) {}
      return;
    }

    // Replay tail first — a page reload should see prior output.
    List<String> replay;
    synchronized (job.tail) {
      replay = new ArrayList<>(job.tail);
    }
    for (String line : replay) {
      trySend(emitter, "log", line);
    }

    if (job.state != State.RUNNING) {
      // Job already terminal — send final event and close.
      trySend(emitter, "done", doneJson(job));
      try { emitter.complete(); } catch (Exception ignore) {}
      return;
    }

    job.emitters.add(emitter);
    emitter.onCompletion(() -> job.emitters.remove(emitter));
    emitter.onTimeout(() -> job.emitters.remove(emitter));
    emitter.onError(t -> job.emitters.remove(emitter));
  }

  // ------------------------------------------------------------------
  // Runner
  // ------------------------------------------------------------------

  private void run(Job job) {
    Path repo = Path.of(props.repoPath());
    Path upSh = repo.resolve("scripts/up.sh");
    if (!Files.isRegularFile(upSh)) {
      finish(job, State.FAILED, -1, "scripts/up.sh not found at " + upSh);
      return;
    }

    List<String> cmd = new ArrayList<>();
    cmd.add("bash");
    cmd.add(upSh.toString());
    cmd.addAll(job.packages);

    ProcessBuilder pb = new ProcessBuilder(cmd)
        .directory(repo.toFile())
        .redirectErrorStream(true); // merge stderr → stdout; script's log_step already tags
    pb.environment().put("AURORA_LAUNCHED_BY", "aurora-dashboard");

    Process proc;
    try {
      proc = pb.start();
    } catch (IOException e) {
      finish(job, State.FAILED, -1, "could not start bash: " + e.getMessage());
      return;
    }

    try (BufferedReader r = new BufferedReader(
        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        onLine(job, line);
      }
      int exit = proc.waitFor();
      finish(job, exit == 0 ? State.SUCCESS : State.FAILED, exit,
          exit == 0 ? null : "up.sh exited " + exit);
    } catch (IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      finish(job, State.FAILED, -1, "reader interrupted: " + e.getMessage());
    }
  }

  private void onLine(Job job, String line) {
    synchronized (job.tail) {
      job.tail.addLast(line);
      while (job.tail.size() > TAIL_MAX_LINES) job.tail.removeFirst();
    }
    if (job.logFile != null) {
      try {
        Files.writeString(job.logFile, line + "\n", StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND);
      } catch (IOException ignore) { /* best-effort */ }
    }
    fanout(job, "log", line);
  }

  private void finish(Job job, State state, int exit, String reason) {
    job.state = state;
    job.exitCode = exit;
    job.finishedAt = Instant.now();
    job.failureReason = reason;
    if (reason != null) {
      onLine(job, "[aurora] " + reason);
    }
    fanout(job, "done", doneJson(job));
    for (SseEmitter e : job.emitters) {
      try { e.complete(); } catch (Exception ignore) {}
    }
    job.emitters.clear();
    activeJobId.compareAndSet(job.id, null);
    audit.record(null, "onboarding.launch.finish", "job:" + job.id,
        "{\"state\":\"" + state.name().toLowerCase() + "\",\"exit\":" + exit + "}");
  }

  // ------------------------------------------------------------------
  // SSE plumbing
  // ------------------------------------------------------------------

  private void fanout(Job job, String event, String data) {
    for (SseEmitter e : job.emitters) trySend(e, event, data);
  }

  private void trySend(SseEmitter emitter, String event, String data) {
    try {
      emitter.send(SseEmitter.event().name(event).data(data));
    } catch (Exception e) {
      // Client gone — best-effort remove happens via completion callback.
    }
  }

  private void sendHeartbeats() {
    String id = activeJobId.get();
    if (id == null) return;
    Job job = jobs.get(id);
    if (job == null || job.state != State.RUNNING) return;
    for (SseEmitter e : job.emitters) trySend(e, "ping", String.valueOf(System.currentTimeMillis()));
  }

  private String doneJson(Job job) {
    long durMs = job.finishedAt == null ? 0
        : Duration.between(job.startedAt, job.finishedAt).toMillis();
    return "{\"state\":\"" + job.state.name().toLowerCase()
        + "\",\"exit_code\":" + job.exitCode
        + ",\"duration_ms\":" + durMs
        + (job.failureReason == null ? "" : ",\"reason\":\"" + jsonEscape(job.failureReason) + "\"")
        + "}";
  }

  static String jsonEscape(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }

  private static String toJsonArray(List<String> xs) {
    StringBuilder b = new StringBuilder("[");
    for (int i = 0; i < xs.size(); i++) {
      if (i > 0) b.append(",");
      b.append("\"").append(jsonEscape(xs.get(i))).append("\"");
    }
    return b.append("]").toString();
  }

  // ------------------------------------------------------------------
  // Types
  // ------------------------------------------------------------------

  public enum State { RUNNING, SUCCESS, FAILED }

  public static class Job {
    public final String id;
    public final List<String> packages;
    public final Instant startedAt = Instant.now();
    public volatile Instant finishedAt;
    public volatile State state = State.RUNNING;
    public volatile int exitCode = -1;
    public volatile String failureReason;
    public volatile Path logFile;

    final Deque<String> tail = new ArrayDeque<>();
    final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    Job(String id, List<String> packages) {
      this.id = id;
      this.packages = packages;
    }

    public Map<String, Object> toStatusMap() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", id);
      m.put("state", state.name().toLowerCase());
      m.put("packages", packages);
      m.put("started_at", startedAt.toString());
      m.put("finished_at", finishedAt == null ? null : finishedAt.toString());
      m.put("exit_code", state == State.RUNNING ? null : exitCode);
      m.put("failure_reason", failureReason);
      List<String> t;
      synchronized (tail) { t = new ArrayList<>(tail); }
      // Cap tail at 200 for status responses; full tail lives in the log file.
      int from = Math.max(0, t.size() - 200);
      m.put("tail", t.subList(from, t.size()));
      return m;
    }
  }

  public static class LaunchInProgressException extends RuntimeException {
    public final String activeJobId;
    public LaunchInProgressException(String activeJobId) {
      super("a launch job is already in progress: " + activeJobId);
      this.activeJobId = activeJobId;
    }
  }
}
