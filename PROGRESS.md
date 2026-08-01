# Aurora productionize progress

- [x] step-1 UX spec (c5aa293) — Started 2026-08-01, Finished 2026-08-01
- [x] step-2 E2E infra (03c8aa4)
- [x] step-3 E2E tests + baseline (34 passing / 16 failing / 10 skipped, 60 total)
- [x] step-4 iter-1 plan

- [x] step-5 iter-1 impl (fc3b13a, E2E: 28/30 focused pass; done-launch 2/2; no-cli done 4/4; 15 out-of-scope failures deferred to iter-2/3)
- [x] step-6 iter-2 plan (living checklist + per-package probing; blocker-first ordering; AdGuard first-run detection)
- [x] step-7 iter-2 impl (7a81cb2, E2E: 39 pass / 14 fail / 9 skip; iter-2 target suites 39 pass with 1 in-scope fail deferred to iter-3 for AdGuard E2E fixture)
- [x] step-8 iter-3 plan (error-recovery classifier + always-Retry + TLS sudo scrub; media sub-checklist Prowlarr→Sonarr→Radarr→Bazarr→Seerr; SMB TCP-open probe + per-OS mount panels — target: +5 pass, 0 new fails, skips −3)
- [x] step-9 iter-3 impl — target #1 shipped, #2 and #3 deferred (E2E: 40 pass / 18 fail / 4 skip full-suite; focused retry-clean 44/14/4; error-recovery.spec.ts 2/3 green, third self-skips; TLS sudo cp scrubbed; LaunchService.classify() + failureCode wired; 8/8 LaunchServiceClassifierTests; media substack + SMB reachability deferred to iter-4 per plan §handoff)
- [x] step-10 morning briefing (MORNING_BRIEFING.md — TL;DR + Sarah flow post-iter-3 + E2E scorecard baseline→iter-3 + reset commands for live :8090 + 9 ranked follow-ups + 5 manual checks for Bruce)
- [x] step-P1 closure (a9eed72 or later, no-cli-admin green)
- [x] step-P2 closure (E2E: 40/18/4 no change vs iter-3; LaunchServiceTests 6/6; live /api/services/status ~140 ms; log cap 5 MB; docker read timeout 3s; sessionStorage launch-job persistence + reconnecting badge)
