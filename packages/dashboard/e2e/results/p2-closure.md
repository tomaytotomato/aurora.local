# E2E — P2 closure re-run

**Ran:** 2026-08-01 (post P2 reliability residuals)
**Against:** aurora-e2e compose project on :8091

## Stats

| Passed | Failed | Skipped | Total | Δ vs iter-3 |
|-------:|-------:|--------:|------:|:------------|
|     40 |     18 |       4 |    62 | no change   |

Full-suite pass count meets the ≥40 baseline. Zero regressions. Same set of
pre-existing wizard-happy-path selector defects and one `no-cli-instructions`
`/onboarding/admin` cold-hydration flake as iter-3 — none introduced by this
closure chain.

## Backend unit tests

- `LaunchServiceTests`: **6/6 pass** (added `log_file_is_bounded_when_up_sh_spews_more_than_cap`)
- Full backend suite: 75 tests, 73 pass, 2 pre-existing SB4 bean-override errors (`AuroraApplicationTests`, `PackagesServiceTests`) — flagged in briefing since iter-2, unrelated to this chain.

## Live smoke on :8090

- `GET /api/health` → `{"db":true,"status":"ok","docker":"29.6.2"}`
- `GET /api/services/status` → 200 in **~140 ms** — well under the new 3 s DockerClient responseTimeout.

## Changes shipped

1. **P2 #3 log rotation** — `LaunchService.appendToLogFile()` caps on-disk
   log at 5 MB, writes a single truncation marker, sets `job.logTruncated=true`.
   In-memory tail + SSE fan-out unaffected. Backed by a new unit test.
2. **P2 #4 DockerClient timeouts** — `DockerClientConfig` now sets
   `connectionTimeout=2 s`, `responseTimeout=3 s`, `maxConnections=20` on the
   `ApacheDockerHttpClient.Builder`. Live smoke confirms normal-case latency
   is unchanged.
3. **P2 #5 launch-job persistence** — `OnboardingDone.vue` persists
   `{jobId,startedAt}` to `sessionStorage['aurora.launch.currentJob']` on
   `startLaunch()` success; on mount it rehydrates via
   `GET /onboarding/launch/{id}` and jumps straight into `LaunchProgress` or
   `DoneChecklist` per snapshot state. Cleared on `success`/`failed`/retry/
   `Go to my dashboard`. `LaunchProgress.vue` gained a 30 s SSE-frame
   watchdog that renders a `Reconnecting…` badge (`data-testid=
   "launch-reconnecting"`, `role="status"`) while EventSource auto-reconnects.
