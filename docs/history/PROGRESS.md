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
- [x] step-P3 closure (dc28c45, error-recovery 3/3)
- [x] step-deploy (DEPLOY_STATUS.md, live=sha256:99c2375f, backup=.state.yml.bak.1785594847 + /data/aurora.db.bak.1785594875)

- [ ] step-dash-1 UX spec (open) — Started 2026-08-01, dashboard-home spec at docs/UX_SPEC_DASHBOARD.md; scope = 4 confirmed blockers from logs/dashboard-bugs-2026-08-01.md (Start 409, header `be1523c08f0f.undefined`, System NaN, Metrics axios 404); non-goals: metrics backend, security module, theme overhaul; awaiting iter-1 plan (step-dash-2) then worker impl
- [x] step-dash-3 iter-2 polish plan (logs/dashboard-polish-iter-2.md — 6 items ranked Blocker×3 / Friction×3; Blockers: P1 System card 'Health' bare-label → 'Resources', P2 header anatomy grid + kill 'idle' badge + divider-dot, P6 kill 'Review checks →' link (SecurityPosture.vue is stub with fabricated score=78 and 4 fake findings — sends Sarah to invented data); Frictions: P3 unified card anatomy (eyebrow→h3→subtitle→body), P4 §4 empty-state glyph pattern applied to all four cards, P5 Metrics strip col-span-6 h-32 → h-16 half-height; non-goals reasserted: no new backend endpoints, no design-system refactor; DoD = 41 baseline +3 pass 0 new fails; ship blockers as one commit, frictions as one commit; SCRATCHPAD follow-up: /security stub gate for v0.3)
- [x] step-dash-2 bugs 1+2+3+4 impl (b110c13 bug 1 hostname + TopBar; 407366a bugs 2+3 NaN + metrics gated + DashboardHome rewrite; 47ef0f3 bug 4 POST /api/services/{pkg}/start + ServicesController + tests; 45/45 backend tests; E2E 41 pass / 18 fail / 3 skip vs iter-3 baseline 40/18/4 = +1 pass, 0 new fails, −1 skip; live 8090 rebuilt and healthy on new image)
- [x] step-dash-4 iter-2 polish impl (342c8ef blockers P1+P2+P6; a7bc916 frictions P3+P4+P5; f155f8d e2e port-collision fix; live 8090 rebuilt on new image 6c67f0aa; new spec dashboard-home-polish.spec.ts adds 7 acceptance assertions — 5 self-skip on fresh e2e box behind auth; E2E: 41 pass / 18 fail / 10 skip = same 41 pass, 0 new fails vs baseline 41/18/3; 7 new self-skips = the 7 new polish assertions; SPA bundle sanity-scanned — Resources/Security posture/Metrics land next release/Warming up/Watching for common misconfigurations/Nothing has changed all present; banned labels Health/Posture/CPU, memory, disk/Review checks all absent)
