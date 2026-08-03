package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.domain.Package;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
  /**
   * P2 #3: on-disk cap for {@code /data/launch-logs/launch-<id>.log}. Once
   * a job's log crosses this size we append a single truncation marker and
   * stop writing to disk (in-memory tail and SSE fan-out continue normally).
   * Keeps disk usage bounded without adding rotation complexity — each job
   * gets its own file, no cross-run growth.
   */
  static final long LOG_FILE_MAX_BYTES = 5L * 1024 * 1024;
  private static final String LOG_DIR = "/data/launch-logs";

  private final AuroraProperties props;
  private final AuditEventRepo audit;
  /**
   * Optional manifest lookup used by {@link #resolveBudgetSeconds(String)}
   * to render the effective per-package + total start budget into the
   * launch header. Null in unit tests that stub {@code up.sh}; wired by
   * Spring in production so {@code startLaunch()} logs an honest budget
   * derived from {@code packages/<name>/manifest.yml}
   * ({@code requires.start_budget_seconds}; see
   * {@link Package#startBudgetSeconds()}).
   */
  private final PackagesService packages;
  /**
   * iter-29: optional principal lookup for audit-trail attribution.
   * Null in unit tests so the pre-attribution behaviour (userId=null on
   * audit.record) is preserved; wired by Spring in production so a
   * launch initiated via the DashboardHome Start button records the
   * acting admin's id.
   */
  private final com.tomaytotomato.aurora.services.CurrentUserService currentUser;

  private final Map<String, Job> jobs = new ConcurrentHashMap<>();
  private final AtomicReference<String> activeJobId = new AtomicReference<>(null);

  private final ScheduledExecutorService heartbeat =
      Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "aurora-launch-heartbeat");
        t.setDaemon(true);
        return t;
      });

  /**
   * Test-only constructor. Prod path uses the 4-arg {@code (props, audit,
   * packages, currentUser)} form Spring auto-wires; unit tests stage a
   * fake up.sh and don't exercise budget-header logging or audit
   * attribution.
   */
  public LaunchService(AuroraProperties props, AuditEventRepo audit) {
    this(props, audit, null, null);
  }

  /**
   * Two-arg-with-packages test convenience for the budget-header suite.
   */
  public LaunchService(AuroraProperties props, AuditEventRepo audit, PackagesService packages) {
    this(props, audit, packages, null);
  }

  @Autowired
  public LaunchService(AuroraProperties props, AuditEventRepo audit, PackagesService packages,
                       com.tomaytotomato.aurora.services.CurrentUserService currentUser) {
    this.props = props;
    this.audit = audit;
    this.packages = packages;
    this.currentUser = currentUser;
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
      String header = "# aurora launch " + id + " started " + job.startedAt + "\n"
              + "# packages: " + String.join(",", job.packages) + "\n"
              + "# start_budget: " + renderBudgetHeader(job.packages) + "\n";
      Files.writeString(job.logFile, header, StandardCharsets.UTF_8);
      job.logBytesWritten = header.getBytes(StandardCharsets.UTF_8).length;
    } catch (IOException e) {
      log.warn("could not create launch log file at {}: {}", LOG_DIR, e.getMessage());
      job.logFile = null;
    }

    audit.record(currentUserId(), "onboarding.launch.start", "job:" + id,
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
      appendToLogFile(job, line);
    }
    fanout(job, "log", line);
  }

  /**
   * Best-effort append with a hard on-disk cap. Once {@link #LOG_FILE_MAX_BYTES}
   * is exceeded, a single marker line is written and further disk writes are
   * skipped for the remainder of the job. SSE fan-out and the in-memory tail
   * are unaffected — subscribers still see everything.
   */
  private void appendToLogFile(Job job, String line) {
    if (job.logTruncated) return;
    try {
      byte[] payload = (line + "\n").getBytes(StandardCharsets.UTF_8);
      if (job.logBytesWritten + payload.length > LOG_FILE_MAX_BYTES) {
        String marker = "[aurora] log truncated at " + LOG_FILE_MAX_BYTES
            + " bytes; live stream continues in the UI.\n";
        Files.writeString(job.logFile, marker, StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND);
        job.logBytesWritten += marker.getBytes(StandardCharsets.UTF_8).length;
        job.logTruncated = true;
        return;
      }
      Files.write(job.logFile, payload,
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.APPEND);
      job.logBytesWritten += payload.length;
    } catch (IOException ignore) { /* best-effort */ }
  }

  private void finish(Job job, State state, int exit, String reason) {
    job.state = state;
    job.exitCode = exit;
    job.finishedAt = Instant.now();
    if (state == State.FAILED) {
      // Iter-3: classify raw failure into human copy + machine-readable code.
      // Tail lines are inspected so port-conflict / pull-rate / disk-full /
      // docker-down / crash patterns get plain-English reasons Sarah can act on.
      String tail;
      synchronized (job.tail) {
        StringBuilder b = new StringBuilder();
        int from = Math.max(0, job.tail.size() - 200);
        int i = 0;
        for (String line : job.tail) {
          if (i++ < from) continue;
          b.append(line).append('\n');
        }
        tail = b.toString();
      }
      String firstPkg = job.packages.isEmpty() ? "your services" : job.packages.get(0);
      Classified c = classify(tail, exit, firstPkg, reason);
      job.failureReason = c.reason;
      job.failureCode = c.code;
    } else {
      job.failureReason = reason;
      job.failureCode = null;
    }
    if (job.failureReason != null) {
      onLine(job, "[aurora] " + job.failureReason);
    }
    fanout(job, "done", doneJson(job));
    for (SseEmitter e : job.emitters) {
      try { e.complete(); } catch (Exception ignore) {}
    }
    job.emitters.clear();
    activeJobId.compareAndSet(job.id, null);
    audit.record(currentUserId(), "onboarding.launch.finish", "job:" + job.id,
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
        + (job.failureCode == null ? "" : ",\"failure_code\":\"" + jsonEscape(job.failureCode) + "\"")
        + "}";
  }

  // ------------------------------------------------------------------
  // Failure classifier (iter-3)
  //
  // Best-effort English-only pattern match over the tail buffer. Falls
  // through to `unknown` on no match so we never synthesise fake success.
  // Reason strings MUST be user copy — no `sudo `, `docker `, `bash `,
  // `./scripts/`, `ssh ` substrings. `LaunchServiceClassifierTests` enforces.
  // ------------------------------------------------------------------

  record Classified(String reason, String code) {}

  static Classified classify(String tail, int exitCode, String firstPackage, String rawReason) {
    String t = tail == null ? "" : tail;
    String tl = t.toLowerCase();

    // Port conflict: "bind: address already in use" (docker & compose both emit).
    if (tl.contains("address already in use") || tl.contains("port is already allocated")) {
      String port = findPort(t);
      String p = port == null ? "a required port" : ("Port " + port);
      return new Classified(
          p + " is already in use by another program on this box. Free it up or pick a different port.",
          "port_conflict");
    }

    // Container registry rate-limit (Docker Hub etc).
    if (tl.contains("toomanyrequests") || tl.contains("429 too many requests")
        || tl.contains("pull access denied") || tl.contains("rate limit")) {
      return new Classified(
          "The container registry is rate-limiting Aurora right now. Wait a couple of minutes and try again.",
          "pull_rate_limited");
    }

    // Disk full.
    if (tl.contains("no space left on device")) {
      return new Classified(
          "The disk Aurora is installing to is full. Free up space or pick a different drive.",
          "disk_full");
    }

    // Docker daemon unreachable.
    if (tl.contains("cannot connect to the docker daemon")
        || tl.contains("is the docker daemon running")) {
      return new Classified(
          "Aurora can't reach the container engine on this box. Check that the container service is running.",
          "docker_down");
    }

    // Bind-mount missing / wrong type. Classic symptom when Aurora runs
    // inside a container and shells out to compose via the host socket,
    // but the repo isn't mounted at the same absolute path on the host.
    // Runtime message shape (OCI runtime create failed):
    //   "not a directory: Are you trying to mount a directory onto a file"
    // or the inverse ("not a file") when the host path is missing entirely
    // and docker auto-creates an empty directory in its place.
    if ((tl.contains("not a directory") || tl.contains("not a file"))
        && (tl.contains("mount") || tl.contains("rootfs") || tl.contains("bind"))) {
      return new Classified(
          "Aurora couldn't find one of its config files on the host. "
              + "This usually means the aurora repo isn't mounted at the same path "
              + "inside and outside the aurora container. Check AURORA_REPO_PATH_HOST.",
          "bind_mount_missing");
    }

    // Container crash: line indicates a container Exited with non-zero soon
    // after starting. Compose prints e.g. `Container aurora-media-sonarr Exited (1)`.
    if (tl.contains(" exited (") || tl.contains("exited with code") || tl.contains("unhealthy")) {
      String container = findContainer(t);
      String who = container == null ? firstPackage : container;
      return new Classified(
          who + " started but crashed straight away. Aurora tailed its log to the panel below.",
          "container_crashed");
    }

    // Fallback — never surface the raw shell-y reason.
    return new Classified(
        "Something went wrong bringing up " + firstPackage + ". The log below has the details.",
        "unknown");
  }

  private static final java.util.regex.Pattern PORT_RE =
      java.util.regex.Pattern.compile(":(\\d{2,5})[ :\\\"']|port (\\d{2,5})\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

  private static String findPort(String s) {
    var m = PORT_RE.matcher(s);
    if (m.find()) {
      String a = m.group(1);
      return a != null ? a : m.group(2);
    }
    return null;
  }

  private static final java.util.regex.Pattern CONTAINER_RE =
      java.util.regex.Pattern.compile("Container ([\\w.-]+)", java.util.regex.Pattern.CASE_INSENSITIVE);

  private static String findContainer(String s) {
    var m = CONTAINER_RE.matcher(s);
    return m.find() ? m.group(1) : null;
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
  // A8 (iter-7): per-package start budget resolution.
  //
  // Manifests may declare `requires.start_budget_seconds` to give the UI
  // a longer optimistic-start window for multi-container stacks (media,
  // monitoring, documents, ai, photos, home-automation, dev, privacy).
  // We surface the effective per-package budgets in the launch log
  // header so a post-mortem can tell whether a hung stack ran under a
  // 30s default or the manifest's declared 180s. The frontend has always
  // consumed this via `startBudgetMs()` (frontend/src/api/packages.ts);
  // this is the honest backend read.
  //
  // Package.startBudgetSeconds() clamps to [30, 600]. When PackagesService
  // is null (unit-test constructor path), we render "n/a" without
  // failing the launch — the tail buffer still captures up.sh's own logs.
  // ------------------------------------------------------------------

  String renderBudgetHeader(List<String> pkgs) {
    if (packages == null || pkgs == null || pkgs.isEmpty()) return "n/a";
    StringBuilder b = new StringBuilder();
    int total = 0;
    for (int i = 0; i < pkgs.size(); i++) {
      if (i > 0) b.append(", ");
      String p = pkgs.get(i);
      int budget = resolveBudgetSeconds(p);
      total += budget;
      b.append(p).append('=').append(budget).append('s');
    }
    b.append(" (total=").append(total).append("s)");
    return b.toString();
  }

  int resolveBudgetSeconds(String pkg) {
    if (packages == null || pkg == null) return Package.DEFAULT_START_BUDGET_SECONDS;
    try {
      return packages.find(pkg)
          .map(Package::startBudgetSeconds)
          .orElse(Package.DEFAULT_START_BUDGET_SECONDS);
    } catch (RuntimeException e) {
      // Manifest lookup shouldn't hard-fail a launch. Default and move on.
      log.debug("budget lookup failed for {}: {}", pkg, e.getMessage());
      return Package.DEFAULT_START_BUDGET_SECONDS;
    }
  }

  /**
   * iter-29: audit-trail attribution. Returns the authenticated admin id
   * when a session exists; null in unit tests + wizard-phase paths where
   * no session is yet available. Null propagates to {@code audit_event.user_id}
   * matching the pre-attribution behaviour.
   */
  Long currentUserId() {
    if (currentUser == null) return null;
    try {
      return currentUser.currentUserId().orElse(null);
    } catch (RuntimeException e) {
      log.debug("currentUserId lookup failed: {}", e.getMessage());
      return null;
    }
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
    public volatile String failureCode;
    public volatile Path logFile;
    /** P2 #3: bytes written to logFile; once >= {@link #LOG_FILE_MAX_BYTES} we stop appending. */
    volatile long logBytesWritten = 0L;
    /** True once the truncation marker has been written so we do it exactly once. */
    volatile boolean logTruncated = false;

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
      m.put("failure_code", failureCode);
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
