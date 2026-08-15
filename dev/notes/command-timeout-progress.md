# command seam timeouts — progress log

Branch `fix/command-seam-timeouts`. Working from a read-only audit of
`ProcessCommandRunner` / `LaunchService` / `UpdatesService` that surfaced two
findings sharing one root cause: the command seam has no way to bound or stop
a wedged process.

- Finding 1: `ProcessCommandRunner.stream` has no timeout at all, and
  `LaunchService` has no cancel endpoint, so a stalled `docker compose pull`
  or `up -d` wedges the onboarding wizard's single-in-flight lock permanently.
- Finding 2: `CommandRunner.run`'s declared timeout doesn't fire, because the
  output-reading loop blocks to EOF before `waitFor(timeout)` is ever reached.

## 2026-08-15 — slice 1: reproduce finding 2, then fix `run` and `stream`

Reproduced finding 2 before touching the fix: `ProcessCommandRunnerTests`
(`RunTimeout.bounds_wall_clock_time_even_though_the_process_never_produces_output`)
runs `sh -c "sleep 3"` with a declared 250ms timeout. Against the pre-fix
`run()` this took the full ~3s and returned a *successful* exit 0 —
`result.timedOut()` was false, because the read-to-EOF loop only returned
(and only then checked `waitFor`) once the process closed its own stdout by
exiting naturally. Ran it once against the original implementation to confirm
that failure mode before making any change, per the brief ("don't take the
audit on trust").

Fixed `ProcessCommandRunner.run`: output reading now happens on its own
daemon thread while the calling thread enforces the deadline via
`Process.waitFor(remaining, MILLISECONDS)` against a wall-clock deadline
computed at start. On expiry, `killTree()` kills the process **and every
descendant** (`Process.descendants()`, snapshotted before the parent dies,
each `destroyForcibly()`'d) — a plain `destroyForcibly()` only reaches the
direct child, and `bash up.sh` running `docker compose` is exactly the shape
where that matters: the interesting failure is the grandchild being orphaned
and left running. `Result.timedOut()` already existed as a distinguishable
outcome (separate from `error`/non-zero exit); the fix makes the mechanism
that was supposed to produce it actually reachable.

Gave `stream` an **inactivity ceiling** (10 minutes in production, injectable
via a package-private constructor for tests) rather than a total-duration
cap. A `docker compose pull` of a large image can legitimately run for many
minutes on home broadband, so killing on the clock rather than on behaviour
would turn "this box has a slow uplink" into "this box is broken". Docker
prints per-layer progress throughout a real pull, so genuine silence this
long means the process (or the daemon under it) has actually stopped doing
anything — which is Finding 1's scenario, and the one worth killing for. A
watchdog thread polls every 250ms; on expiry it kills the tree and `stream`
throws `CommandTimeoutException` (new, unchecked — the process is already
dead by the time the caller sees it).

Added `CommandRunner.CancelToken` (a plain `AtomicBoolean` wrapper) and a new
5-arg `stream` overload that takes one; the same watchdog thread also checks
`cancelToken.isCancelled()` each poll and, on cancel, kills the tree and
throws `CommandCancelledException`. The old 4-arg `stream` is now a default
method that passes a fresh, never-cancelled token, so `JobService` (which
still calls the 4-arg form) needed no changes — and it now gets the
inactivity ceiling for free, which is a real improvement for `/jobs` work
too, not just onboarding launch.

`FakeCommandRunner` updated to implement the 5-arg `stream`; added
`stubHangsUntilCancelled(argvContains)` so a test can simulate "still
running" without a real OS process or a real sleep — it blocks (busy-poll,
outside the fake's monitor lock so other fake calls aren't blocked
meanwhile) until the token is cancelled, then throws
`CommandCancelledException`, matching what the real runner does.

Tests: `ProcessCommandRunnerTests` (6 cases, real `sh` processes throughout —
the bug lives in the interaction between a blocking read and a real OS pipe,
which a fake can't reproduce):
- `RunTimeout`: the finding-2 repro above; a descendant-kill proof (`sleep
  4321 & wait` — asserts via `ProcessHandle.allProcesses()` that neither the
  parent shell nor the backgrounded `sleep` survive); a plain success-path
  regression (`echo hello; echo world` collects both lines, not timed out).
- `StreamInactivityTimeout`: kills a silent `sleep 10` within a 200ms
  injected ceiling and throws `CommandTimeoutException`; a process that
  emits five lines ~80ms apart over ~400ms (i.e. total runtime exceeds a
  300ms ceiling but no single gap does) completes normally — proves this is
  an inactivity timer, not a total-duration one in disguise.
- `StreamCancellation`: starts a `sleep 10` on a background thread, cancels
  after 150ms, asserts `CommandCancelledException` within ~3s rather than
  the full 10s.

Every test carries `@Timeout(10)` as a backstop and is bounded to a couple of
seconds even in the un-fixed case, so the suite stays fast (no 20-second
sleeps).

Full suite: 667 → 673 (`mvn test`, ~22s → ~22s, no slowdown).

## 2026-08-15 — slice 2: LaunchService cancel + release the lock

Plumbed `job.cancelToken` (a `CommandRunner.CancelToken`) into
`LaunchService.Job`, passed to `commands.stream(...)` in `run(Job)`. Added
`LaunchService.cancel(String jobId)`: no-op (returns false) for an unknown
id or a job that isn't `RUNNING`; otherwise cancels the token and returns
true. **Service-level only** — checked `openapi.yaml` first and there is no
cancel path declared for `/onboarding/launch/{id}` or `/jobs/{id}` (grepped
for `cancel|abort|kill|stop`, nothing). Per the brief, not inventing one:
`OpenApiConformanceTest` fails the build the moment a controller method
exists without a matching spec entry, so an HTTP surface here needs a spec
change first. `cancel()` exists so that change is a thin controller method
away, not a redesign.

`LaunchService.run(Job)` now catches `CommandCancelledException` and
`CommandTimeoutException` separately from the generic `IOException` path,
each going straight to a new `finishClassified(job, reason, code)` rather
than through the tail-pattern `classify()` — there's no guessing to do when
the caller already knows exactly why the job stopped. Refactored `finish()`
into `finish()` (still tail-classifies for a genuine non-zero exit) +
`finishClassified()`, both funnelling into a shared `finishWith(...)` that
does the state/exitCode/log/audit/lock-release bookkeeping. `activeJobId`
release already lived in that shared tail, so both paths get it for free —
this is the actual fix for "a wedged or cancelled job releases the
single-in-flight lock".

New failure codes on `LaunchService.Job`: `"cancelled"` and `"stalled"`.
Neither is in `openapi.yaml`'s `JobFailureCode` enum, but that enum only
covers `/jobs`, not `/onboarding/launch/{id}` — `LaunchStatus` doesn't
declare `failure_code` as a schema property at all (pre-existing; it's one
of the nine gaps a previous commit already documented), so this doesn't
create a new conformance problem, only extends an already-undeclared field.

Tests: `LaunchServiceCancellationTests`, real `up.sh` scripts on disk (same
shape as `LaunchServiceTests` — `LaunchService` always talks to a real
`ProcessCommandRunner`, so a fake wouldn't exercise the actual kill path):
- `Cancel`: cancelling a `sleep 30` launch marks it FAILED with
  `failureCode "cancelled"` and lets a second `startLaunch()` succeed
  immediately (proves the lock is released, not just that the field says
  so); cancelling an unknown id and cancelling an already-finished job are
  both no-ops.
- `StalledLaunch`: constructs `LaunchService` with a `ProcessCommandRunner`
  built via the package-private 200ms-inactivity constructor (the 5-arg
  `@Autowired`-shaped constructor already accepted an injected
  `CommandRunner`, so no new seam was needed) and a script that sleeps 5s
  with no output; asserts `failureCode "stalled"` and, again, that the lock
  is released.

Full suite: 673 → 677. `mvn test`: **677/677 passing**, ~24s total.

## What a reviewer should check hardest

1. `killTree()`'s ordering — `proc.descendants()` is read *before*
   `destroyForcibly()` on the parent. If that order were reversed, a
   fast-reaping OS could reparent a grandchild before it's enumerated,
   leaking exactly the orphan this fix exists to prevent. Covered by
   `kills_descendant_processes_not_just_the_direct_child`, but the ordering
   argument is worth re-deriving by eye, not just trusting the green test.
2. The watchdog/reader race in `stream()`: `outcome` is set by the watchdog
   thread and read by the main thread after the reader loop returns. There
   is an inherent small window (bounded by `WATCHDOG_POLL_INTERVAL`, 250ms)
   where a process finishing naturally right at the inactivity deadline
   could race against being killed. Judged acceptable given the 10-minute
   production ceiling, but worth a second opinion.
3. Whether `"stalled"`/`"cancelled"` deserve to become real `LaunchStatus`
   schema fields (`failure_code`) in `openapi.yaml` rather than riding along
   as an undeclared property — that's the spec change this report flags as
   still needed if cancellation should ever be operator-facing over HTTP.
4. `FakeCommandRunner.stream`'s busy-poll loop (`Thread.sleep(5)`) for the
   hang-until-cancelled stub — deliberately outside the synchronized block
   so it doesn't block other fake calls, but it is still a poll loop; fine
   for tests, not a pattern to copy into production code.
5. JobService was deliberately left untouched beyond what the interface
   default gives it for free (the inactivity ceiling). It still has no
   cancel path and wasn't in scope — flagging in case that's a bigger gap
   than assumed.
