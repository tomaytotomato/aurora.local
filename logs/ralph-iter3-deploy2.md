# D2 — deploy checkpoint after P1 batch (V1+V2+V3 + P1a+P1b+P1c)

**Timestamp:** 2026-08-02 09:59 (post-rebuild)
**HEAD:** `142148b` on `rename/aurora` (11 commits since `fd8ea9c`)
**Image:** `aurora-dashboard:0.1.0` id `b801b470a283`
**Container created:** 2026-08-02T08:57:42.511808841Z
**Live URL:** http://192.168.0.110:8090

## Curl matrix — unauthenticated smoke

| Endpoint | Expected | Observed | Verdict |
|---|---|---|---|
| `GET  /api/system` | 401 | **401** | ✅ auth guard holding |
| `GET  /api/services/status` core | `state="running"` | **running** | ✅ B1 landed |
| `GET  /api/onboarding/env` | `hostname=aurora, lanIp=192.168.0.110` | ✅ exact | ✅ P1a wire ready |
| `GET  /api/onboarding/status` | `complete=true` | ✅ | ✅ |

## Deployed bundle grep (aggregated across every JS/CSS chunk)

| Expected present | Count | Notes |
|---|---:|---|
| `data-theme` | 2 | ✅ V2 dark-mode scaffold |
| `aurora-bg` | 2 | ✅ V1 photo BG layer class |
| `reach-info` | 1 | ✅ P1a ReachInfo data-test hook |
| `reach-copy-mdns` | 1 | ✅ P1a Copy button |
| `reach-copy-ip` | 1 | ✅ P1a Copy button |
| `security-empty` | 1 | ✅ P1b honest empty-state marker |
| `securityScanner` | 2 | ✅ P1b capability flag surface |
| `Metrics land next` | 1 | ✅ earlier polish still landed |
| `auroraTheme` | 1 | ✅ V2 localStorage key |
| `p-8` | 2 | ✅ B3 dashboard card padding |

| Expected absent | Count | Notes |
|---|---:|---|
| `aurora.aurora.local` | **0** | ✅ B2 dedup holding |
| `Review checks` | 0 | ✅ iter-2 polish holding |
| `be1523c08f0f` | 0 | ✅ container-hostname leak fix holding |
| `UFW enabled` | 0 | ✅ P1b fabricated finding deleted |
| `fail2ban running` | 0 | ✅ P1b fabricated finding deleted |
| `Backup not scheduled` | 0 | ✅ P1b fabricated finding deleted |
| `Two warnings, no criticals` | 0 | ✅ P1b fabricated score-narrative deleted |
| `NaN` | 4 | acceptable — vendor math internals in main index chunk, never user-visible |
| `Request failed` | 1 | acceptable — axios's built-in error string, never rendered per B3-of-earlier-chain |

## P1c mdns-audit script — in-container proof

Copied `scripts/mdns-audit.sh` to `/tmp` inside aurora and ran it:

- `which avahi-browse dig` → `/usr/bin/avahi-browse /usr/bin/dig` ✅ **Dockerfile deps landed**
- `avahi-browse --version` → `avahi-browse 0.8` ✅
- `dig -v` → `DiG 9.20.26` ✅
- Script executes cleanly, writes `/tmp/mdns-audit-2026-08-02.txt`, and emits the collision-check summary.
- Container multicast dig times out (expected — bridge network doesn't cross host mDNS). Script intended to be run on the host or with `--hostname` override.

## Backend + frontend build health

- Backend Maven test suite: **88/88 green** (unchanged from D1).
- Frontend `vue-tsc --noEmit`: clean.
- Vite bundle emitted, DashboardHome + main chunk hashes rotated (V2 dark-mode wiring + V3 pill + P1a ReachInfo + P1b Security rewrite all present).

## What Bruce sees after refresh + hard-reload (bookmark http://192.168.0.110:8090)

1. Aurora photo background floods the app shell around the cards (scrim="strong" so cards remain readable).
2. Header centre carries the **All good / Not started / Attention needed** aggregate health pill.
3. Header right region has a sun/moon toggle **before** the interpunct + username. Click toggles `data-theme`, persists to `localStorage.auroraTheme`.
4. System card shows `aurora.local` and `192.168.0.110` LAN IP with Copy buttons — no more mDNS-only reach.
5. Hand-typed `/security` renders "Watching for common misconfigurations" empty-state with M4 milestone list — no fabricated score, no fake findings.
6. Sidebar has three entries (Overview / Packages / Settings) — Security link hidden until the capability flag flips.

## Residuals leaving this checkpoint

- BL5 auth fixture (~7 E2E assertions still self-skip; unlocked next).
- Media stack still off (Bruce can Start from dashboard; polls up to 180 s cleanly now).
- Iter-4 backlog untouched (BL1..BL6, TDs).
- P0/P1 batches fully closed and live.
