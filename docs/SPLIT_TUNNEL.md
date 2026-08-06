# VPN egress split-tunnel

The goal: send selected apps (RDTClient, qBittorrent, anything
privacy-sensitive) out through a VPN tunnel, while the box itself and
every other service keep using the normal WAN gateway. Per-app, not
all-or-nothing.

## How it works

aurora does split-tunnelling at the **container** level, not the host
level. A gateway container (`gluetun`, in `packages/privacy`) brings up
a WireGuard tunnel to your VPN provider. Any app that shares the
gateway's network namespace egresses through that tunnel; every other
app, and the host's own default route, is untouched.

```
                    ┌────────────────────────────┐
   WAN ◀────────────│ host + most containers      │
                    │ (adguard, sonarr, jellyfin…) │
                    └────────────────────────────┘
                    ┌────────────────────────────┐
 VPN  ◀── gluetun ◀─│ qbittorrent, rdtclient, …   │  network_mode:
 (WireGuard)        │ (share gluetun's netns)      │  service:gluetun
                    └────────────────────────────┘
```

An app opts in with one line in its compose service:

```yaml
network_mode: "service:gluetun"
```

That is the whole mechanism. It is chosen over host-level policy
routing (fwmark/`ip rule`) because netns sharing is the only approach
that is safe to flip on and off from a UI without rewriting the host's
routing table.

### The kill-switch matters

gluetun's firewall means an app in its namespace gets **no network at
all** if the tunnel is down. Traffic cannot leak to the WAN while the
VPN is dropped. This is the reason to route a torrent client through the
gateway rather than trusting the app's own bind-to-interface setting.

## Current state

- **qBittorrent** already egresses through gluetun (the `torrent`
  compose profile). See `packages/media/compose.yml`.
- The gateway can now be started on its own with the `vpn` profile,
  independently of the torrent client, so it can serve other apps.
- Provider and credentials live in `packages/privacy/.env`
  (`VPN_SERVICE_PROVIDER`, `WIREGUARD_PRIVATE_KEY`, `WIREGUARD_ADDRESSES`,
  …). gluetun is provider-agnostic.

## Tunnelling another app (worked example: RDTClient)

There are four moving parts, because an app in the gateway's namespace
gives up its own networking:

1. **Attach it.** In the app's service, remove `networks: [aurora_net]`
   and add:
   ```yaml
   network_mode: "service:gluetun"
   depends_on:
     gluetun:
       condition: service_healthy
   ```
2. **Publish its port on the gateway.** A namespaced app cannot publish
   its own ports; the port must be declared on the `gluetun` service
   instead (e.g. add `- "6500:6500"` to gluetun for RDTClient).
3. **Point Caddy at the gateway.** Other containers can no longer reach
   the app by its own name (it has no address on `aurora_net`). Its
   vhost must proxy to `gluetun` on that port:
   `reverse_proxy gluetun:6500`.
4. **Start the gateway.** Bring gluetun up with the `vpn` (or `torrent`)
   profile so the namespace exists before the app starts.

The same steps generalise to any app. To use a different provider or
region for a specific app, run a second gateway container (a copy of the
`gluetun` service under another name) and attach the app to that one
instead.

## Ingress (future)

This document is about **egress** (apps reaching out through a VPN).
Secure **ingress** (reaching aurora from outside, e.g. from your phone)
is a separate tunnel and a later piece of work: a WireGuard server such
as `wg-easy`, with peers managed from the dashboard's VPN page. The
egress design here does not preclude it; they are independent tunnels.

## Planned: per-app toggle in the dashboard

Doing steps 1–3 by hand is exactly what the dashboard should automate.
The planned control plane feature: a per-app switch that, when flipped,
rewrites the app's `network_mode`, moves its published port onto the
gateway, updates the Caddy vhost, and restarts just that app. Until then,
tunnelling an app is the manual edit above. Tracked in `PLAN.md`.
