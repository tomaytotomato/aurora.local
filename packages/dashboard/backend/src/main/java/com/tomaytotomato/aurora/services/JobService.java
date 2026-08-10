package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The registry behind {@code /api/jobs}: every long-running operation
 * Aurora runs on the operator's behalf, with its log.
 *
 * <p>This generalises what {@link LaunchService} has done for the
 * onboarding launch since iter-1. Adding an app, updating one, starting a
 * stopped package, taking a backup, restoring a snapshot, syncing parity
 * and deploying a custom stack are all the same shape, and the frontend
 * already treats them that way — one {@code JobLogPanel} streams all of
 * them.
 *
 * <p>Three deliberate differences from {@code LaunchService}:
 *
 * <ul>
 *   <li><b>No single-in-flight rule.</b> Launch is global and exclusive;
 *       these are not. Updating Jellyfin while a backup runs is
 *       reasonable, and refusing it would be arbitrary.</li>
 *   <li><b>Replay from the beginning, not from a tail window.</b> The
 *       frontend opens the stream of jobs that finished hours ago and
 *       expects the whole log; see {@code JobLogPanel}'s contract. The tail
 *       buffer is therefore the log, capped, rather than a window over a
 *       file.</li>
 *   <li><b>camelCase on the wire.</b> The {@code /jobs} schema in
 *       openapi.yaml is camelCase; the older launch endpoint is snake_case
 *       and stays that way. Casing follows the wire, not a convention.</li>
 * </ul>
 *
 * <p>State is in memory only. A restart loses the job records, which is
 * correct: whatever the job started keeps running, and a job log is
 * interesting for minutes rather than weeks.
 */
@Service
public class JobService {

  private static final Logger log = LoggerFactory.getLogger(JobService.class);

  /**
   * Per-job log ceiling. Generous, because the whole log is replayed to
   * late subscribers and a compose pull of a large stack is verbose, but
   * bounded because this is heap.
   */
  static final int MAX_LOG_LINES = 5_000;

  /**
   * How many finished jobs to keep. Beyond this the oldest terminal jobs
   * are evicted; running jobs are never evicted. Sized so a busy hour of
   * updates stays inspectable without the map growing for the life of the
   * process.
   */
  static final int MAX_RETAINED_JOBS = 200;

  private final AuditEventRepo audit;
  private final CurrentUserService currentUser;
  private final CommandRunner commands;

  private final Map<String, Job> jobs = new ConcurrentHashMap<>();

  private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
    Thread t = new Thread(r, "aurora-job");
    t.setDaemon(true);
    return t;
  });

  private final ScheduledExecutorService heartbeat =
      Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "aurora-job-heartbeat");
        t.setDaemon(true);
        return t;
      });

  public JobService(AuditEventRepo audit, CurrentUserService currentUser, CommandRunner commands) {
    this.audit = audit;
    this.currentUser = currentUser;
    this.commands = commands;
    // Keepalive for proxies that would otherwise close an idle SSE
    // connection mid-pull. The frontend shows a "Reconnecting…" badge
    // after 30s of silence, so 15s keeps it quiet during a slow step.
    heartbeat.scheduleAtFixedRate(this::sendHeartbeats, 15, 15, TimeUnit.SECONDS);
  }

  // ------------------------------------------------------------------
  // Kinds
  // ------------------------------------------------------------------

  /**
   * Matches the {@code JobKind} enum in openapi.yaml. The wire form is the
   * lowercase hyphenated name, so {@code UPDATE_CHECK} serialises as
   * {@code update-check}.
   */
  public enum Kind {
    ENABLE, DISABLE, UPDATE, UPDATE_CHECK, START, RESTART, BACKUP, RESTORE,
    PARITY_SYNC, PARITY_SCRUB, DEPLOY;

    public String wire() {
      return name().toLowerCase().replace('_', '-');
    }
  }

  /** Matches {@code JobState} in openapi.yaml. */
  public enum State {
    QUEUED, RUNNING, SUCCESS, FAILED;

    public String wire() {
      return name().toLowerCase();
    }

    public boolean terminal() {
      return this == SUCCESS || this == FAILED;
    }
  }

  // ------------------------------------------------------------------
  // Submitting work
  // ------------------------------------------------------------------

  /**
   * Run a command as a job, streaming its output.
   *
   * @param kind       what this is, for the headline the frontend renders
   * @param target     package name, snapshot id, stack name; null for box-wide
   * @param workingDir where to run; typically the repo root
   * @param argv       program and arguments, never shell-interpreted
   * @return the created job, already running
   */
  public Job submitCommand(Kind kind, String target, Path workingDir, List<String> argv) {
    return submit(kind, target, job -> {
      int exit = commands.stream(workingDir, Map.of("AURORA_INVOKED_BY", "aurora-dashboard"),
          argv, line -> append(job, line));
      if (exit != 0) {
        throw new JobFailedException("exited " + exit, exit);
      }
    });
  }

  /**
   * Run arbitrary work as a job. The body appends its own log lines via
   * {@link #append} and throws {@link JobFailedException} to fail.
   *
   * <p>Used where the work is not a single process: a Kopia snapshot
   * driven over its HTTP API, a compose rewrite followed by a restart, a
   * registry sweep across every package.
   */
  public Job submit(Kind kind, String target, JobBody body) {
    Job job = new Job(UUID.randomUUID().toString(), kind, target);
    jobs.put(job.id, job);
    evictOldTerminalJobs();

    audit.record(currentUserId(), "job.start", kind.wire() + (target == null ? "" : ":" + target), null);

    workers.execute(() -> {
      job.state = State.RUNNING;
      try {
        body.run(job);
        finish(job, State.SUCCESS, 0, null);
      } catch (JobFailedException e) {
        finish(job, State.FAILED, e.exitCode, e.getMessage());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        finish(job, State.FAILED, -1, "interrupted");
      } catch (Exception e) {
        // Anything unexpected still has to produce a terminal state, or the
        // panel spins forever. The reason goes through the classifier like
        // any other failure, so the operator gets copy rather than a stack
        // trace.
        log.warn("job {} ({}) threw: {}", job.id, kind.wire(), e.toString());
        append(job, "[aurora] " + e.getClass().getSimpleName() + ": " + e.getMessage());
        finish(job, State.FAILED, -1, e.getMessage());
      }
    });

    return job;
  }

  /** Append a line to a job's log and fan it out to any watchers. */
  public void append(Job job, String line) {
    if (line == null) return;
    synchronized (job.lines) {
      job.lines.addLast(line);
      if (job.lines.size() > MAX_LOG_LINES) {
        job.lines.removeFirst();
        if (!job.truncated) {
          job.truncated = true;
          log.debug("job {} log exceeded {} lines; oldest dropped", job.id, MAX_LOG_LINES);
        }
      }
    }
    fanout(job, "log", line);
  }

  // ------------------------------------------------------------------
  // Reading
  // ------------------------------------------------------------------

  public Optional<Job> find(String jobId) {
    return Optional.ofNullable(jobs.get(jobId));
  }

  /** Newest first, optionally filtered. Nulls mean "any". */
  public List<Job> list(State state, Kind kind) {
    return jobs.values().stream()
        .filter(j -> state == null || j.state == state)
        .filter(j -> kind == null || j.kind == kind)
        .sorted(Comparator.comparing((Job j) -> j.startedAt).reversed())
        .toList();
  }

  /**
   * Attach a watcher. Replays the whole log first, then either streams the
   * rest or, for a job that has already finished, sends the terminal event
   * and closes immediately.
   */
  public void subscribe(String jobId, SseEmitter emitter) {
    Job job = jobs.get(jobId);
    if (job == null) {
      try {
        emitter.completeWithError(new IllegalArgumentException("no such job"));
      } catch (Exception ignore) {
        // Client already gone.
      }
      return;
    }

    List<String> replay;
    synchronized (job.lines) {
      replay = new ArrayList<>(job.lines);
    }
    for (String line : replay) {
      trySend(emitter, "log", line);
    }

    if (job.state.terminal()) {
      trySend(emitter, "done", statusJson(job));
      try {
        emitter.complete();
      } catch (Exception ignore) {
        // Client already gone.
      }
      return;
    }

    job.watchers.add(emitter);
    emitter.onCompletion(() -> job.watchers.remove(emitter));
    emitter.onTimeout(() -> job.watchers.remove(emitter));
    emitter.onError(t -> job.watchers.remove(emitter));
  }

  // ------------------------------------------------------------------
  // Internals
  // ------------------------------------------------------------------

  private void finish(Job job, State state, int exitCode, String rawReason) {
    job.exitCode = exitCode;
    job.finishedAt = Instant.now();
    if (state == State.FAILED) {
      String subject = job.target == null ? "this" : job.target;
      String tail;
      synchronized (job.lines) {
        int from = Math.max(0, job.lines.size() - 200);
        StringBuilder b = new StringBuilder();
        int i = 0;
        for (String line : job.lines) {
          if (i++ < from) continue;
          b.append(line).append('\n');
        }
        tail = b.toString();
      }
      var classified = JobFailureClassifier.classify(tail, exitCode, subject, rawReason);
      job.failureReason = classified.reason();
      job.failureCode = classified.code();
    }
    // Set last: a watcher that reads state must already see the reason.
    job.state = state;

    if (job.failureReason != null) {
      // Goes through append so late subscribers replaying the log see why
      // it stopped, not just that it did.
      append(job, "[aurora] " + job.failureReason);
    }

    fanout(job, "done", statusJson(job));
    for (SseEmitter e : job.watchers) {
      try {
        e.complete();
      } catch (Exception ignore) {
        // Client already gone.
      }
    }
    job.watchers.clear();

    audit.record(currentUserId(), "job.finish",
        job.kind.wire() + (job.target == null ? "" : ":" + job.target),
        "{\"state\":\"" + state.wire() + "\",\"exit\":" + exitCode + "}");
  }

  private void fanout(Job job, String event, String data) {
    for (SseEmitter e : job.watchers) trySend(e, event, data);
  }

  private void trySend(SseEmitter emitter, String event, String data) {
    try {
      emitter.send(SseEmitter.event().name(event).data(data));
    } catch (Exception e) {
      // Client gone; the completion callback removes it.
    }
  }

  private void sendHeartbeats() {
    for (Job job : jobs.values()) {
      if (job.state.terminal() || job.watchers.isEmpty()) continue;
      for (SseEmitter e : job.watchers) {
        trySend(e, "ping", String.valueOf(System.currentTimeMillis()));
      }
    }
  }

  /**
   * Keep the map bounded. Only terminal jobs are candidates — evicting a
   * running job would strand its watchers and lose a log someone is
   * reading.
   */
  private void evictOldTerminalJobs() {
    if (jobs.size() <= MAX_RETAINED_JOBS) return;
    jobs.values().stream()
        .filter(j -> j.state.terminal())
        .sorted(Comparator.comparing(j -> j.finishedAt == null ? j.startedAt : j.finishedAt))
        .limit(Math.max(0, jobs.size() - MAX_RETAINED_JOBS))
        .forEach(j -> jobs.remove(j.id));
  }

  private Long currentUserId() {
    if (currentUser == null) return null;
    try {
      return currentUser.currentUserId().orElse(null);
    } catch (RuntimeException e) {
      log.debug("currentUserId lookup failed: {}", e.getMessage());
      return null;
    }
  }

  /** The terminal SSE payload: the same shape as {@code GET /jobs/{id}}. */
  String statusJson(Job job) {
    StringBuilder b = new StringBuilder("{");
    b.append("\"id\":\"").append(job.id).append("\",");
    b.append("\"kind\":\"").append(job.kind.wire()).append("\",");
    b.append("\"target\":").append(job.target == null ? "null" : "\"" + esc(job.target) + "\"").append(',');
    b.append("\"state\":\"").append(job.state.wire()).append("\",");
    b.append("\"startedAt\":\"").append(job.startedAt).append("\",");
    b.append("\"finishedAt\":").append(job.finishedAt == null ? "null" : "\"" + job.finishedAt + "\"").append(',');
    b.append("\"exitCode\":").append(job.state.terminal() ? String.valueOf(job.exitCode) : "null").append(',');
    b.append("\"failureCode\":").append(job.failureCode == null ? "null" : "\"" + esc(job.failureCode) + "\"").append(',');
    b.append("\"failureReason\":").append(job.failureReason == null ? "null" : "\"" + esc(job.failureReason) + "\"");
    b.append('}');
    return b.toString();
  }

  private static String esc(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
  }

  // ------------------------------------------------------------------
  // Types
  // ------------------------------------------------------------------

  /** The body of a job. Appends to the log via {@link #append}; throws to fail. */
  @FunctionalInterface
  public interface JobBody {
    void run(Job job) throws Exception;
  }

  /** Thrown by a job body to record a clean failure with an exit code. */
  public static class JobFailedException extends RuntimeException {
    public final int exitCode;

    public JobFailedException(String message, int exitCode) {
      super(message);
      this.exitCode = exitCode;
    }

    public JobFailedException(String message) {
      this(message, -1);
    }
  }

  public static class Job {
    public final String id;
    public final Kind kind;
    public final String target;
    public final Instant startedAt = Instant.now();

    public volatile State state = State.QUEUED;
    public volatile Instant finishedAt;
    public volatile int exitCode = -1;
    public volatile String failureReason;
    public volatile String failureCode;
    volatile boolean truncated = false;

    final Deque<String> lines = new ArrayDeque<>();
    final List<SseEmitter> watchers = new CopyOnWriteArrayList<>();

    Job(String id, Kind kind, String target) {
      this.id = id;
      this.kind = kind;
      this.target = target;
    }

    /** Snapshot without the log, for {@code GET /jobs}. */
    public Map<String, Object> toSummary() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", id);
      m.put("kind", kind.wire());
      m.put("target", target);
      m.put("state", state.wire());
      m.put("startedAt", startedAt.toString());
      m.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
      // Null while running rather than -1: the frontend renders it, and an
      // exit code that has not happened yet is not a number.
      m.put("exitCode", state.terminal() ? exitCode : null);
      m.put("failureCode", failureCode);
      m.put("failureReason", failureReason);
      return m;
    }

    /** Snapshot with the log, for {@code GET /jobs/{id}}. */
    public Map<String, Object> toStatus() {
      Map<String, Object> m = toSummary();
      List<String> snapshot;
      synchronized (lines) {
        snapshot = List.copyOf(lines);
      }
      m.put("tail", snapshot);
      return m;
    }
  }
}
