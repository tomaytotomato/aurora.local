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

## 2026-08-14 — all 13 endpoints implemented, 546 tests green

Commits so far: progress log → domain records + WireGuardKeys →
Flyway V4 schema + repos → VpnService/VpnController +
OpenVpnService/OpenVpnController (all 13 paths mapped). `mvn test`:
546 (543 baseline + 3 new `WireGuardKeysTests`), 0 failures.
`OpenApiConformanceTest` confirms all 13 `/vpn/*` paths now show as
implemented; no controller-level integration tests written yet for
the new domain — that's the next slice, and the one that actually
proves the honest-state scenarios (not-configured, no peers, peers
connected, stale handshake, gateway down) rather than just "it
compiles".

Gap I'm not happy about but chose deliberately: **`GET
/vpn/peers/{id}/config` and `GET /vpn/peers/{id}/qrcode` return 409 for
every peer, always**, after the one-time reveal in `POST /vpn/peers`.
`VPN_PAGE_DESIGN.md` explicitly and repeatedly says a peer's private
key must never be retrievable a second time ("no reveal=1... the
server never needs to store a peer's private key at all") — but the
same document also lists persistent "Download" and "QR" row actions
on the Peers table hitting exactly those two endpoints, which can only
ever produce a genuinely working file if the key *is* stored
somewhere. Those two statements contradict each other. I picked the
safer reading (don't persist, refuse cleanly with a clear 409 message)
over the more feature-complete one (persist it, keep the row actions
working forever) because the task's own hard requirement is "private
keys never in a response body" and the design doc's rationale section
argues that position at length. If the owner would rather have a
working re-download at the cost of storing peer private keys at rest,
that is a five-minute change to `VpnPeerRepo`/`VpnService`, but it's a
security posture decision he should make on purpose, not one I should
make for him by default.

Also flagging two smaller assumptions made while implementing, since
neither is pinned by the OpenAPI schema (which only constrains the
wire *shape*, not these values):

- **Default DNS pushed to peers is always `1.1.1.1`.** The design doc
  says it should default to the Privacy package's AdGuard LAN IP if
  that package is enabled. Wiring up that cross-package lookup felt
  like scope creep for tonight given the actual `packages/vpn` compose
  bundle doesn't exist yet either (out of scope per the design doc's
  own §7). Easy to fix later in `VpnService.defaultDns()`.
- **Split-tunnel `allowedIps` LAN range is a guessed `/24`** off
  `SystemService.lanIp()`'s first three octets, not a real router
  query. Fine for the common flat-/24 home network, wrong for anyone
  running VLANs. See `VpnService.lanCidrGuess()`.

Next: controller integration tests (`VpnControllerIntegrationTest`,
`OpenVpnControllerIntegrationTest`) covering the five honest-state
scenarios, then the capability flag flip.
