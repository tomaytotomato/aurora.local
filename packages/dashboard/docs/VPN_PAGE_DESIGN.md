# VPN configuration page — design spec

Status: design only, no frontend code written. Implementation-ready.
Audience: whoever builds `packages/dashboard/frontend/src/views/VpnConfig.vue`
and the matching `src/api/vpn.ts` module.

## 0. What this page is for

Aurora's own **inbound** VPN server: remote access into the LAN from
outside, for a person's own phone or laptop. WireGuard is the default
and the only protocol that gets the full, polished flow (QR onboarding,
live status, kill switch). OpenVPN is offered as a secondary, clearly
de-emphasised option for the rare device that can't do WireGuard.

This is not the same thing as `packages/privacy`'s Gluetun sidecar.
Gluetun is an **outbound** client tunnel that anonymises traffic leaving
the media stack (qBittorrent). This page's WireGuard server does the
opposite job: it lets traffic *in*, from a trusted device, to the LAN.
Same three letters, opposite direction. The page says this explicitly
in the header (see §2) because it is the single most likely point of
confusion for anyone who has already set up Gluetun and sees "VPN" again
in the nav.

## 1. Where it lives

- New top-level route `/vpn`, sibling to `/security` and `/settings` in
  `router/index.ts`, mounted under `AppShell`.
- New Sidebar nav entry, same shape as the existing `NavItem[]` list in
  `Sidebar.vue`:
  ```ts
  { to: '/vpn', label: 'VPN', icon: '…', requiresCapability: 'vpn' }
  ```
  Gated on `SystemCapabilities.vpn` (new boolean, same pattern as
  `securityScanner`) rather than on package-enabled state, so the nav
  entry doesn't wink in and out while a fresh install is still deciding
  whether to install the package. The page itself handles the
  not-installed state (§3).
- Backed by a new package, `packages/vpn/` (WireGuard + optionally
  OpenVPN as a sidecar container), following the existing package
  contract. Out of scope for this spec, but two things the page design
  assumes: the package category enum gains a value (`network`, since
  neither `core` nor `privacy` quite fits an inbound server), and the
  package exposes a `ServiceStatus` entry so it shows up in the existing
  Settings/Done-checklist plumbing for free.
- `PackageDetail.vue`'s Overview tab, when `name === 'vpn'`, gets one
  extra line: "Full configuration →" linking to `/vpn`. The generic
  package detail page stays generic; VPN's bespoke UI lives at its own
  route rather than inside the Config tab, because peer management and
  QR codes don't fit the tab's env-var-editor shape.

## 2. Page states

Same state vocabulary as `SecurityPosture.vue` and `PackageDetail.vue`:

| State | Trigger | Render |
|---|---|---|
| `loading` | first mount, before `GET /vpn/status` resolves | `Skeleton` blocks, same shape as `PackageDetail`'s overview skeleton |
| `error` | status fetch failed | `Alert variant="destructive"` + "Try again" `Button` |
| `not-installed` | `vpn` package not enabled | Empty-state `Card`, CTA button enables the package |
| `not-configured` | package enabled, no server keypair generated yet | Empty-state `Card`, CTA button generates the server config (first-run action, not a full wizard) |
| `ready` | package enabled, server configured | Full page: relationship banner + Tabs (Overview / Peers / Advanced) |
| `degraded` (sub-state of `ready`) | server running but `reachable: false` | `Badge tone="warn"` on the header + inline `Alert variant="warning"` explaining the likely cause (router port forward) |

The `degraded` state matters more here than on most Aurora pages: a
WireGuard server that starts fine but sits behind an unforwarded router
port is *silently useless*, and a dashboard that shows a cheerful green
tick in that state is actively lying to the person relying on it to get
home. The page must probe external reachability and say so honestly
(see the `reachable` field in §5) rather than only reporting "container
is running".

## 3. Layout, section by section

### 3.1 Header

```
eyebrow: Network
h1: VPN
[Badge: running / stopped / degraded / off]
```

One sentence under the heading, always visible regardless of state:

> Remote access into aurora.local from anywhere, over WireGuard. This is
> not the outbound VPN that anonymises the media stack — see Privacy →
> Gluetun for that.

The second half of that sentence is a `router-link` to `/packages/privacy`.

### 3.2 Not-installed state

`Card` (`data-state="empty"`), same visual pattern as
`SecurityPosture.vue`'s "scanner off" card and `PackagesList.vue`'s empty
filter state:

- Icon (shield/lock outline, matching the existing inline-SVG icon style
  used elsewhere on this page rather than an icon library)
- "Reach your LAN from anywhere"
- One paragraph: what it does, that WireGuard is the default, that this
  is separate from the media stack's Gluetun tunnel
- `Button` "Enable VPN" → `PackagesApi.enable('vpn')`, then re-fetch
  status (same call the `PackagesList` cards already make)

### 3.3 Not-configured state

Package is enabled and the container is up, but no server keypair
exists yet (first boot). `Card`, same empty-state shape:

- "Generate your server configuration"
- Short paragraph: Aurora will generate a WireGuard keypair, pick a free
  UDP port (default 51820), and prefill sensible defaults from what it
  already knows about this box.
- `Button` "Generate configuration" → `POST /vpn/config/init` (see §5),
  then transition to `ready`.

No form is shown before this button is pressed. The opinionated call is
to generate first, then let the person tune the pre-filled values on the
Overview tab, rather than presenting a blank multi-field form on first
contact. Onboarding-flow fatigue is real; this page should not add
another wizard.

### 3.4 Ready state — Tabs

Reuses `Tabs.vue` exactly as `PackageDetail.vue` does:

```html
<Tabs v-model="activeTab" :tabs="[
  { value: 'overview', label: 'Overview' },
  { value: 'peers', label: 'Peers' },
  { value: 'advanced', label: 'Advanced' },
]">
```

#### Overview tab

Two `Card`s in a two-column grid (`grid grid-cols-2 gap-4`, matching
`PackageDetail`'s overview grid):

**Card 1 — Tunnel status** (live, SSE-backed, see §4)
- eyebrow "Live" / h3 "Tunnel"
- Big status word (`text-3xl font-mono`, matching `PackageDetail`'s
  "Status" card): `running` / `stopped` / `degraded`
- `dl` rows: Interface (`wg0`), Listen port, Peers online / total
  (`2 / 3`), Reachable from outside (`yes` / `no, check port forwarding`
  / `checking…`)
- If `reachable === false`: inline `Alert variant="warning"` under the
  dl: "Aurora can't confirm this is reachable from outside your LAN.
  Forward UDP port 51820 to this box on your router, then refresh."

**Card 2 — Server configuration** (edit form)
- eyebrow "Config" / h3 "Server"
- `Label` + `Input` pairs, pre-filled from `GET /vpn/config`:
  - Endpoint (hostname or public IP the box is reachable at) — prefilled
    from `OnboardingEnv.lanIp` if no DDNS name is known yet, with a hint
    that a dynamic-DNS hostname is more reliable than a raw IP for
    anyone on a residential connection
  - Listen port — default `51820`
  - DNS pushed to peers — default: the Privacy package's AdGuard LAN IP
    if that package is enabled, else `1.1.1.1`. This is the one place
    the two VPN concepts touch on purpose: if you've already set up
    LAN-wide ad blocking, your remote devices should get the same DNS
    when they're tunnelled in, so ad blocking still applies away from
    home.
  - Server tunnel address — default `10.66.66.1/24`
  - MTU — default `1420`
- `Button` "Save" (disabled while unchanged / while saving) → `PUT /vpn/config`
- Secondary, visually quieter action below a thin rule: "Regenerate
  server key" (text button, `text-muted-foreground hover:text-foreground`,
  same treatment as the Dismiss link on `SecurityPosture`) → opens a
  `Dialog` confirming this invalidates every existing peer's config, since
  it's a destructive, hard-to-reverse action.

#### Peers tab

- Header row: h3 "Peers" + count pill (`Badge tone="ok"` showing
  `2 online`), `Button` "Add peer" on the right, same header layout as
  the Settings "LAN aliases" card.
- Empty state (`data-state="empty"`, `Card`, same shape as `mdns-empty`):
  "No devices yet. Add your phone or laptop to reach aurora.local from
  anywhere."
- Otherwise a `Table` (`TableHeader`/`TableBody`/`TableRow`/`TableHead`/
  `TableCell`, matching `SettingsView`'s mDNS and audit tables exactly):

  | Name | Status | Allowed IPs | Data | Last handshake | |
  |---|---|---|---|---|---|
  | Bruce's phone | `Badge tone="ok"` online | `192.168.1.0/24, 10.66.66.2/32` | ↓120 MB ↑8 MB | 2 min ago | QR · Download · Kill switch: on · Remove |

  - Status badge derives client-side from `lastHandshakeAt`: online if
    within the last 3 minutes, same "derive don't trust a status field"
    principle as `packageStatus()` in `api/packages.ts`.
  - "Kill switch: on/off" is a small inline `Badge tone="neutral"` /
    `tone="warn"`, not an editable control in the row — it's fixed at
    creation time because changing it means regenerating that peer's
    `.conf` (see §3.4.1).
  - Row actions: "QR" opens a `Dialog` showing the QR image (from
    `GET /vpn/peers/{id}/qrcode`) for re-scanning on a second device;
    "Download" hits `GET /vpn/peers/{id}/config` (browser downloads the
    `.conf`, same pattern as the existing `caddy-root.crt` endpoint);
    "Remove" is a text button that opens a small `Dialog` confirmation
    (peers are cheap to recreate, but removing one blind is still a
    quiet outage for whoever's on that device).

##### 3.4.1 Add peer dialog

`Dialog` with:
- `Label`/`Input` "Name" (e.g. "Bruce's phone")
- A short choice, rendered as two `Checkbox` rows rather than a
  dropdown, because this is the one decision that matters and it
  deserves to be seen, not hidden in a `Select`:
  - **Split tunnel — access this LAN only** (default, checked): the
    peer can reach devices on the home network; all other traffic on
    the phone/laptop stays on its normal connection.
  - **Full tunnel with kill switch**: all of the peer's traffic routes
    through this box, and the generated config blocks all traffic if
    the tunnel drops. For someone who wants their home connection as
    their VPN when out and about, not just LAN access.
  These are mutually exclusive (radio behaviour implemented as two
  `Checkbox`es bound to one `ref`, matching the existing project's
  preference for native-feeling primitives over adding a RadioGroup
  component for one use).
- Footer: `Button variant="secondary"` Cancel, `Button` "Add peer"

On success, the dialog's content swaps in place (matches `Dialog`'s
existing single-panel pattern, no chained modals) to a **one-time
reveal** panel:
- `Alert variant="warning"`: "This is the only time you'll see this
  key. Scan the code or download the file now."
- QR image (rendered from the base64 PNG in the response body)
- `Button variant="secondary"` "Download .conf"
- A collapsed `<details>`-style disclosure (same pattern as Settings'
  "Suppressed findings" toggle) revealing the raw config text, for
  anyone setting up a WireGuard client that can't scan a QR
- `Button` "Done" closes the dialog and refreshes the peer table

The private key is never returned again after this response. If it's
lost, the fix is removing the peer and adding a new one, not a "reveal"
endpoint — WireGuard private keys don't get the `reveal=1` treatment
that env-var secrets get elsewhere in Aurora, because there's no
legitimate reason to look at one twice.

#### Advanced tab (OpenVPN)

Deliberately the quietest tab on the page. Content:

- `Alert variant="info"`: "WireGuard is faster, simpler, and the
  recommended option above. Only turn this on if you have a device that
  can't run a WireGuard client (some older routers, certain corporate
  device profiles)."
- `Checkbox` + `Label` "Also run an OpenVPN server" (off by default)
- When on, a much smaller form than the WireGuard side: Port, Protocol
  (`Select`: UDP / TCP)
- Client list re-uses the same `Table` shape as WireGuard peers, but
  without QR codes (OpenVPN's `.ovpn` profile isn't meaningfully
  scannable) and without the split/full-tunnel choice at creation time
  (kept as a single "Add client" → download `.ovpn` flow). This is the
  point where the design intentionally does less than for WireGuard —
  the brief for this page is "opinionated default", not "two fully
  equal protocols".

## 4. Live status

`GET /vpn/status/stream` (SSE), following the exact pattern already
used for `/services/status/stream`:

- A new composable `useVpnStatusStream()`, a near-copy of
  `useServiceStatusStream.ts`: subscribe on mount, 3-strikes-in-30s
  fallback to polling `GET /vpn/status` every 5s, pause on
  `visibilitychange`, dispose on unmount. Re-using the existing
  composable's failure-ladder logic (rather than inventing a new one)
  keeps the two live-status surfaces behaving identically for the
  person watching them.
- Named SSE event: `vpn-status`, payload is a `VpnStatus` (§5).
- The mock SSE handler (`sseResponse` in `mocks/sse.ts`) emits one
  snapshot immediately, then a tick every 4s that nudges `peersOnline`
  and `reachable` around, so the Overview tab visibly updates in dev
  without needing a real tunnel — same trick `services` and
  `containers/events` mocks already use.

## 5. API surface (mock layer + `openapi.yaml`)

New tag `vpn`, new module `src/api/vpn.ts`. All fields camelCase, in
line with the majority convention (`packages`, `mdns`, `security`) —
this is a fresh domain, no existing snake_case wire shape to match.

### Types

```ts
export type VpnMode = 'wireguard' | 'openvpn';
export type VpnRunState = 'running' | 'stopped' | 'degraded' | 'not-configured';
export type AllowedIpsMode = 'split' | 'full';

export interface VpnStatus {
  runState: VpnRunState;
  interface: string | null;        // e.g. "wg0", null when not-configured
  listenPort: number | null;
  endpoint: string | null;         // host:port once known
  peersTotal: number;
  peersOnline: number;             // handshake within the last 3 minutes
  /** null = not checked yet; the backend probes an external service. */
  reachable: boolean | null;
  lastCheckedAt: string | null;    // ISO-8601 UTC
  generatedAt: string;             // ISO-8601 UTC, snapshot time
}

export interface VpnConfig {
  endpointHost: string;            // DDNS name or public IP; may be ''
  listenPort: number;              // default 51820
  dns: string;                     // default: AdGuard LAN IP, else 1.1.1.1
  serverAddress: string;           // tunnel subnet, default "10.66.66.1/24"
  mtu: number;                     // default 1420
  serverPublicKey: string | null;  // null until first init
}

export interface VpnPeer {
  id: string;
  name: string;
  publicKey: string;
  allowedIps: string;              // what's actually pushed to the client
  killSwitch: boolean;             // true when allowedIpsMode was 'full'
  enabled: boolean;
  lastHandshakeAt: string | null;  // ISO-8601 UTC
  rxBytes: number;
  txBytes: number;
  createdAt: string;
}

/** Returned once, from POST /vpn/peers. Never retrievable again. */
export interface VpnPeerSecret {
  peer: VpnPeer;
  privateKey: string;
  qrPngBase64: string;
  confText: string;
}

export interface OpenVpnConfig {
  enabled: boolean;
  port: number;                    // default 1194
  protocol: 'udp' | 'tcp';
}

export interface OpenVpnClient {
  id: string;
  name: string;
  createdAt: string;
  lastConnectedAt: string | null;
}
```

### Endpoints

| Method | Path | Notes |
|---|---|---|
| GET | `/vpn/status` | `VpnStatus` snapshot |
| GET | `/vpn/status/stream` | SSE, named event `vpn-status`, same payload |
| GET | `/vpn/config` | `VpnConfig`; 404-shaped as `not-configured` before init |
| POST | `/vpn/config/init` | First-run: generate keypair + defaults. Returns `VpnConfig`. 409 if already configured. |
| PUT | `/vpn/config` | Partial update, mirrors `PATCH /onboarding` conventions but as PUT since the whole editable surface is on one form. Returns `VpnConfig`. |
| POST | `/vpn/server/rotate-key` | Regenerates the server keypair. Returns `VpnConfig`. Destructive — every peer's `.conf` becomes wrong until re-downloaded. |
| GET | `/vpn/peers` | `VpnPeer[]` |
| POST | `/vpn/peers` | Body `{ name: string, allowedIpsMode: AllowedIpsMode }`. 201, returns `VpnPeerSecret`. |
| DELETE | `/vpn/peers/{id}` | 204 |
| POST | `/vpn/peers/{id}/toggle` | Flips `enabled` without deleting. 200, returns `VpnPeer`. |
| GET | `/vpn/peers/{id}/config` | `text/plain`, `Content-Disposition: attachment`, mirrors the existing `caddy-root.crt` pattern |
| GET | `/vpn/peers/{id}/qrcode` | `image/png` |
| GET | `/vpn/openvpn/config` | `OpenVpnConfig` |
| PUT | `/vpn/openvpn/config` | `OpenVpnConfig` |
| GET | `/vpn/openvpn/clients` | `OpenVpnClient[]` |
| POST | `/vpn/openvpn/clients` | Body `{ name }`. 201, returns `{ client: OpenVpnClient, confText: string }` (no QR, per §3.4's "less for OpenVPN" call) |
| DELETE | `/vpn/openvpn/clients/{id}` | 204 |

Add the `vpn` tag and these paths/schemas to `packages/dashboard/openapi.yaml`
alongside the existing `mdns` section, following its exact style
(`$ref` parameters for path params, `JobRef`-style small response
objects where relevant — though none of these are async jobs, so none
of them need the 202/`JobRef` shape the package enable/disable
endpoints use).

### Mock layer

- New fixture module `mocks/fixtures/vpn.ts`: seed 2-3 `VpnPeer` rows
  (one online, one offline, one with `killSwitch: true`), a
  `VpnConfig` with the defaults above, and a placeholder base64 PNG for
  QR responses (a static 1x1 or a genuinely rendered small QR — either
  is fine for MSW purposes).
- New handler group in `mocks/handlers.ts`, same shape as the existing
  `mdns` group, added to `state.ts` if peer add/remove needs to persist
  within a session (it should, so removing a peer in the mock UI stays
  removed on tab switch).
- SSE handler for `/vpn/status/stream` follows the `services` handler's
  `progress()`-style tick function: nudge `peersOnline` and flip
  `reachable` between `true`/`null` a couple of times so the Overview
  tab visibly does something in a demo without real hardware.

## 6. Rationale (the opinionated bits)

- **WireGuard is the only protocol with a QR flow.** Mobile is the
  primary use case for "reach my home network from outside it"; a
  laptop can paste a `.conf` path, a phone cannot. Making WireGuard the
  only tab with a QR code is a deliberate nudge, not an oversight.

- **Split tunnel is the default, not full tunnel.** The stated use case
  is "reach my LAN", not "route all my traffic through my house". A
  full-tunnel default would silently put someone's entire mobile data
  usage through their home upload speed the first time they turn on
  the VPN toggle on their phone, which is a nasty surprise disguised as
  a sensible-looking checkbox. Full tunnel + kill switch stays one
  deliberate click away for the person who does want it.

- **The private key is shown exactly once, ever.** No `reveal=1` query
  parameter like the package env-var editor has. A lost WireGuard key
  means removing the peer and adding a new one. This is standard
  WireGuard practice (the server never needs to store a peer's private
  key at all) and it removes an entire class of "who can see this
  secret" question later.

- **Reachability is probed and reported honestly.** A WireGuard
  container can be perfectly healthy and still be completely useless if
  the home router isn't forwarding the UDP port. Most homelab "my VPN
  doesn't work" reports are exactly this. The status card would be
  actively misleading if it only reported container health.

- **The relationship to Gluetun is stated on the page, not just in
  documentation.** Two unrelated things sharing the word "VPN" inside
  the same dashboard is a real support burden waiting to happen;
  the fix is one sentence in the header, not a FAQ entry.

- **OpenVPN gets deliberately less UI, not equal UI.** Matching
  WireGuard's peer table, QR flow, and split/full-tunnel choice
  feature-for-feature for a protocol Aurora doesn't want to encourage
  would undercut the "WireGuard is the default" framing the brief asks
  for. Less polish is itself the opinion.

## 7. Out of scope for this spec

- Backend implementation (key generation, `wg` CLI invocation, the
  `packages/vpn/` compose bundle and manifest).
- Adding `network` to `PackageCategory` in `api/packages.ts` and
  `openapi.yaml` (needed once the package exists; flagged in §1).
- Router/UPnP auto port-forwarding. The reachability check is
  read-only diagnosis, not remediation — Aurora does not attempt to
  open ports on someone's router automatically.
