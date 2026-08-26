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

  /**
   * The single seam for running anything outside the JVM. Defaulted in the
   * pre-seam constructors so the existing suites, which stage a real
   * up.sh on disk and run it, keep exercising the real thing.
   */
  private final CommandRunner commands;

  /**
   * The Java-native converge ({@link ComposeConvergeService}). When present,
   * {@link #run} uses it and {@code scripts/up.sh} is never touched — this is
   * the production path. Null only in the legacy unit-test constructors,
   * which stage a fake {@code up.sh} and exercise the fallback below.
   */
  private final Converger converger;

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

  public LaunchService(AuroraProperties props, AuditEventRepo audit, PackagesService packages,
                       com.tomaytotomato.aurora.services.CurrentUserService currentUser) {
    this(props, audit, packages, currentUser, new ProcessCommandRunner());
  }

  /**
   * Legacy test constructor: no {@link Converger}, so {@link #run} takes the
   * {@code scripts/up.sh} fallback path the existing suites stage a fake
   * script for.
   */
  public LaunchService(AuroraProperties props, AuditEventRepo audit, PackagesService packages,
                       com.tomaytotomato.aurora.services.CurrentUserService currentUser,
                       CommandRunner commands) {
    this(props, audit, packages, currentUser, commands, null);
  }

  @Autowired
  public LaunchService(AuroraProperties props, AuditEventRepo audit, PackagesService packages,
                       com.tomaytotomato.aurora.services.CurrentUserService currentUser,
                       CommandRunner commands, Converger converger) {
    this.props = props;
    this.audit = audit;
    this.packages = packages;
    this.currentUser = currentUser;
    this.commands = commands;
    this.converger = converger;
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
    if (converger != null) {
      runViaConverger(job);
    } else {
      runViaUpSh(job);
    }
  }

  /**
   * Production path: hand the converge to {@link ComposeConvergeService}.
   * The dashboard runs this from inside its own container, so selfLaunch is
   * always true — the guard excludes the dashboard's own service from
   * {@code up -d} so it is never recreated mid-launch.
   */
  private void runViaConverger(Job job) {
    try {
      int exit = converger.converge(job.packages, true, line -> onLine(job, line), job.cancelToken);
      finish(job, exit == 0 ? State.SUCCESS : State.FAILED, exit,
          exit == 0 ? null : "install exited " + exit);
    } catch (CommandCancelledException e) {
      finishClassified(job, "This launch was cancelled.", "cancelled");
    } catch (CommandTimeoutException e) {
      finishClassified(job,
          "This launch produced no output for a while and Aurora stopped it automatically. "
              + "The container engine or a package may be stuck.",
          "stalled");
    } catch (IOException e) {
      finish(job, State.FAILED, -1, "could not start the install: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      finish(job, State.FAILED, -1, "reader interrupted: " + e.getMessage());
    }
  }

  /** Legacy fallback for the unit tests that stage a fake {@code up.sh}. */
  private void runViaUpSh(Job job) {
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

    // Through the shared seam rather than a second ProcessBuilder: stderr
    // is merged there too, so the script's own log_step tagging still
    // reads in order. job.cancelToken is what lets cancel(jobId) reach the
    // real OS process without this thread having to poll for anything.
    try {
      int exit = commands.stream(repo, Map.of("AURORA_LAUNCHED_BY", "aurora-dashboard"),
          cmd, line -> onLine(job, line), job.cancelToken);
      finish(job, exit == 0 ? State.SUCCESS : State.FAILED, exit,
          exit == 0 ? null : "up.sh exited " + exit);
    } catch (CommandCancelledException e) {
      // The operator (or an automated caller) asked for this to stop.
      // Not a guess, so it bypasses the tail-based classifier entirely.
      finishClassified(job, "This launch was cancelled.", "cancelled");
    } catch (CommandTimeoutException e) {
      // No output for the stream's inactivity ceiling — the up.sh process
      // (and anything docker compose spawned under it) has already been
      // killed by the time this is thrown. This is exactly Finding 1: the
      // single-in-flight lock must not survive this job.
      finishClassified(job,
          "This launch produced no output for a while and Aurora stopped it automatically. "
              + "The container engine or a package may be stuck.",
          "stalled");
    } catch (IOException e) {
      finish(job, State.FAILED, -1, "could not start bash: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      finish(job, State.FAILED, -1, "reader interrupted: " + e.getMessage());
    }
  }

  /**
   * Cancel a running launch. A no-op (returns false) if no job with this
   * id is currently {@link State#RUNNING} — including an unknown id and an
   * already-finished job, which are both "nothing to cancel" from the
   * caller's point of view.
   *
   * <p>Service-level only: there is no HTTP path for this in
   * {@code openapi.yaml} yet. Wiring one up is a contract change that
   * belongs there first — {@code OpenApiConformanceTest} would fail the
   * build the moment a controller method existed without it.
   */
  public boolean cancel(String jobId) {
    Job job = jobs.get(jobId);
    if (job == null || job.state != State.RUNNING) {
      return false;
    }
    job.cancelToken.cancel();
    return true;
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
    Classified classified = null;
    if (state == State.FAILED) {
      // Iter-3: classify raw failure into human copy + machine-readable code.
      // Tail lines are inspected so port-conflict / pull-rate / disk-full /
      // docker-down / crash patterns get plain-English reasons Sarah can act on.
      String firstPkg = job.packages.isEmpty() ? "your services" : job.packages.get(0);
      classified = classify(tailText(job), exit, firstPkg, reason);
    }
    finishWith(job, state, exit, reason, classified);
  }

  /**
   * Terminate a job with an already-known reason and code rather than
   * guessing one from the tail. Used for outcomes the caller already knows
   * the whole story of — cancelled by the operator, or killed for
   * producing no output — where running them through {@link #classify}
   * would be trading a known fact for a pattern match.
   */
  private void finishClassified(Job job, String reason, String code) {
    finishWith(job, State.FAILED, -1, reason, new Classified(reason, code));
  }

  private String tailText(Job job) {
    synchronized (job.tail) {
      StringBuilder b = new StringBuilder();
      int from = Math.max(0, job.tail.size() - 200);
      int i = 0;
      for (String line : job.tail) {
        if (i++ < from) continue;
        b.append(line).append('\n');
      }
      return b.toString();
    }
  }

  private void finishWith(Job job, State state, int exit, String reason, Classified classified) {
    job.state = state;
    job.exitCode = exit;
    job.finishedAt = Instant.now();
    if (classified != null) {
      job.failureReason = classified.reason;
      job.failureCode = classified.code;
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

  /**
   * Kept as the launch-shaped entry point; the patterns now live in
   * {@link JobFailureClassifier} because every job kind fails in the same
   * handful of ways. {@code LaunchServiceClassifierTests} still pins the
   * behaviour through this method.
   */
  static Classified classify(String tail, int exitCode, String firstPackage, String rawReason) {
    var c = JobFailureClassifier.classify(tail, exitCode, firstPackage, rawReason);
    return new Classified(c.reason(), c.code());
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
    /** Set by {@link LaunchService#cancel(String)}; read by the watchdog inside {@code commands.stream}. */
    final CommandRunner.CancelToken cancelToken = new CommandRunner.CancelToken();

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
