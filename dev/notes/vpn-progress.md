# VPN backend — progress log

Running log for `feat/backend-vpn`. Append one entry per commit-sized slice.
If this work stops unexpectedly, this file plus the git log on the branch
is the full picture of what's done.

Baseline before any change: `mvn test` → 543 tests, 0 failures, BUILD SUCCESS.

## 2026-08-14 — start

Read `openapi.yaml` `/vpn/*` (13 paths, tag `vpn`), `docs/SPLIT_TUNNEL.md`
(egress split-tunnel via gluetun netns sharing — a different VPN concept
from this one), and `packages/dashboard/docs/VPN_PAGE_DESIGN.md` (this
page is Aurora's own **inbound** WireGuard/OpenVPN server for remote
access, explicitly *not* gluetun).

Modelled the domain on `UsersController`/`UsersService` +
`AdminUserRepo` (JdbcTemplate repos, Flyway migration, real SQLite in
`AuroraIntegrationTest`) and `UpdatesService`/`StatusController` for the
CommandRunner seam and SSE stream pattern respectively.

Key decisions taken before writing code, flagged here so they're visible
if I don't get to finish the report:

1. **Key generation uses the JDK's own X25519 KeyPairGenerator, not `wg
   genkey`/`wg pubkey`.** Those two `wg` subcommands need stdin piping
   (private key in, public key out), and `CommandRunner.run` takes argv
   only — no stdin. Shelling to `bash -c "wg genkey | wg pubkey"` would
   reintroduce exactly the shell-string risk the seam exists to remove.
   WireGuard keys are raw X25519 keys, so a JCA-generated keypair is
   wire-compatible. `CommandRunner` is still used for `wg show <iface>
   dump` (live peer/handshake/traffic read) — the one place a real
   process call is unavoidable.
2. Server's private key is persisted (needed to eventually write a real
   wg0.conf) but the API-facing `VpnConfig` record and every response
   DTO simply have no field for it — it cannot leak because there is no
   code path that puts it in a response.
3. Peer private keys are never persisted at all, matching the spec's own
   note ("the server never needs to store a peer's private key"). Only
   returned once, in the `POST /vpn/peers` response body.
4. `reachable` always reports `null` (not checked) in this iteration —
   implementing a genuine external-reachability probe tonight would add
   a real network dependency with no way to test it honestly. Reporting
   `null` is the honest answer to "did we check", matching the codebase's
   existing "unknown is a first-class answer" philosophy (see
   `UpdatesService`). Flagged for the owner to revisit once port
   forwarding is actually set up.

Work is happening in small, separately committed slices. See git log on
this branch for the detail; this file tracks state, not diffs.
