# Morning briefing 4 — Caddy TLS unblock (2026-08-02 evening)

**Worker:** Linux + Docker + Caddy specialist
**Branch:** `rename/aurora` — 1 commit atop `eb50432`
**Live entry points now working:**
- http://aurora.local/ → 200
- https://aurora.local/ → 200 (Caddy self-signed cert, install root CA for green padlock)
- http://192.168.0.110/ → 200 (raw IP fallback)
- http://192.168.0.110:8090/ → 200 (unchanged; direct-to-aurora escape hatch)

---

## 1. Root cause of the aurora.local unreachability

**Layer that was broken: no listener on :80/:443.** The Caddy sidecar defined in `packages/core/compose.yml` was never started. `up.sh` brings core up when invoked, but the running box only had the `aurora` container on :8090 — nothing on :80/:443.

Cascade of confusion for Bruce:
- `https://aurora.local/` → connection failed. Correct: nothing was on :443.
- `https://aurora.local:8090/` → secure connection failed. Correct: aurora on :8090 is plain HTTP; his browser upgraded http→https because of cached HSTS from a previous `aurora.local` install (an old home.local dashboard), then rejected the plain-text response as a TLS failure.
- `/etc/hosts` was fine, DHCP reservation was fine, ufw was fine — the box just wasn't listening.

**Ruled out:**
- Firewall — `ufw status` shows `80/tcp` + `443/tcp` ALLOW from 192.168.0.0/24 ✓
- Docker binding — Caddy now binds `0.0.0.0:80` + `0.0.0.0:443` (IPv4 + IPv6 ✓)
- Router / mDNS — router returns NXDOMAIN for aurora.local (as expected, routers don't do mDNS); avahi is broadcasting fine
- IPv6 quirks — Caddy also serves on `[::]:80` and `[::]:443`, tested via `curl -6`

**Kept — this is the Firefox/Safari HSTS trap:**
Bruce's browsers had `Strict-Transport-Security` cached for `aurora.local` from an earlier install. Once cached, the browser refuses http:// for a year (or however long the max-age was set), and forces https://. To avoid *repeating* that trap:
- Caddyfile now has `auto_https disable_redirects` in the global block (was already there) so http:// stays on http:// — no 301 upgrade.
- New reusable `(no_hsts)` snippet stripping any `Strict-Transport-Security` header the upstream might set, imported on every vhost.
- Verified `curl -sS -I https://aurora.local/api/health | grep -i strict` returns empty.

---

## 2. Config diff summary

Two files changed. Both live in the always-enabled `core` package that `up.sh` starts by default.

**`packages/core/caddy/Caddyfile`:**
- Added `(no_hsts)` reusable snippet: `header -Strict-Transport-Security`. Prevents any upstream (or a future browser plugin) from re-locking the box into https-only.
- Added `import no_hsts` on the apex http:// + https:// aurora vhosts.
- Added `handle /caddy-health { respond "caddy ok" 200 }` on the apex vhosts — Caddy-answered fast-path so a docker healthcheck stays green even when the aurora upstream restarts.
- All existing subdomain vhosts (sonarr, radarr, prowlarr, bazarr, seerr, adguard, rdt, flaresolverr) untouched; they already use `tls internal` for wildcard-style self-signed certs on demand.

**`packages/core/compose.yml`:**
- Added docker healthcheck on the caddy service hitting the new `/caddy-health` endpoint. Interval 30s, start_period 10s.
- Ports (`80:80`, `443:443`, `443:443/udp`) unchanged.
- Volumes (Caddyfile, snippets, `/data`, `/config`, `/var/log/caddy`) unchanged — Caddy's PKI state at `/data/caddy/pki/authorities/local/root.crt` persists across restarts, so once you install the root CA on your dev machine you don't have to reinstall it on every rebuild.

---

## 3. Live verification transcript

Ran on the box (192.168.0.110) after Caddy came up:

```
$ curl -sS -v http://aurora.local/api/health 2>&1 | grep -E "^< HTTP|Strict-Transport"
< HTTP/1.1 200 OK

$ curl -sS -v -k https://aurora.local/api/health 2>&1 | grep -E "^< HTTP|Strict-Transport"
< HTTP/2 200

$ curl -sS -k https://aurora.local/api/health
{"db":true,"status":"ok","docker":"29.6.2"}

$ curl -sS http://aurora.local/caddy-health
caddy ok

$ openssl s_client -connect aurora.local:443 -servername aurora.local </dev/null 2>&1 | head -10
depth=1 CN=Caddy Local Authority - ECC Intermediate
verify error:num=20:unable to get local issuer certificate
verify return:1
CONNECTED(00000003)
---
Certificate chain
 0 s: (subject: aurora.local per SAN)
   i:CN=Caddy Local Authority - ECC Intermediate
   a:PKEY: EC, (prime256v1); sigalg: ecdsa-with-SHA256
   v:NotBefore: Aug  2 17:10:42 2026 GMT; NotAfter: Aug  3 05:10:42 2026 GMT

$ openssl s_client ... </dev/null 2>&1 | openssl x509 -noout -text | grep -E "DNS:|Subject:|Not After"
        Subject:
        Not After : Aug  3 05:10:42 2026 GMT
                DNS:aurora.local

$ curl -sS -k -o /dev/null -w '%{http_code}\n' https://aurora.local/api/system/caddy-root.crt
200

$ curl -sS -k https://aurora.local/api/system/caddy-root.crt | head -3
-----BEGIN CERTIFICATE-----
MIIBozCCAUmgAwIBAgIQXHFTgydKVryaYQ4VlgUZ0TAKBggqhkjOPQQDAjAwMS4w
LAYDVQQDEyVDYWRkeSBMb2NhbCBBdXRob3JpdHkgLSAyMDI2IEVDQyBSb290MB4X

$ curl -sS -k --resolve sonarr.aurora.local:443:192.168.0.110 -o /dev/null -w '%{http_code}\n' https://sonarr.aurora.local/
200
$ curl -sS --resolve sonarr.aurora.local:80:192.168.0.110 -o /dev/null -w '%{http_code}\n' http://sonarr.aurora.local/
200

$ docker ps --format '{{.Names}}\t{{.Status}}' | grep caddy
caddy   Up 22 seconds (healthy)
```

Everything green. Certs valid 12h — Caddy `tls internal` rolls them on-demand; the root CA at `/data/caddy/pki/authorities/local/root.crt` persists.

---

## 4. Bruce dev-machine checklist

Run these in order until one identifies the layer that's still broken. Each step is a single command with a clear pass/fail.

**On the box (SSH to 192.168.0.110):**

1. `docker ps | grep caddy` — should show `Up X seconds (healthy)`. If missing: `cd ~/aurora.local/packages/core && docker compose --env-file .env up -d caddy`.
2. `curl -sS -o /dev/null -w '%{http_code}\n' http://localhost/api/health` — expect `200`. If 000: Caddy crashed, `docker logs caddy`.
3. `ss -tlnp | grep -E ':(80|443) '` — expect two lines showing docker-proxy listening on both.
4. `sudo ufw status | grep -E "80/tcp|443/tcp"` — expect ALLOW rules from 192.168.0.0/24.

**On Bruce's dev machine (macOS):**

5. `dig +short aurora.local` — expect `192.168.0.110`. If empty or wrong IP: your `/etc/hosts` entry isn't taking effect (macOS DNS-cache flush: `sudo dscacheutil -flushcache && sudo killall -HUP mDNSResponder`).
6. `curl -sS -v http://aurora.local/api/health` — expect `200` with body. If it fails with `curl: (6) Could not resolve host` your local resolver isn't reading /etc/hosts. If `curl: (7) Failed to connect` there's a network path issue — check LAN routing.
7. Only after 6 passes: try Safari/Firefox/Chrome again. If the browser still refuses http:// and forces https://, that's the cached HSTS from a previous install. See §5.

---

## 5. Clearing cached HSTS + installing the root CA

The one-time HSTS-cache purge, per browser:

### Firefox (macOS + Linux + Windows)
1. Menu → History → Clear Recent History
2. Time range: **Everything**
3. Only check **Site Settings** (or in older versions: Active Logins + Site Preferences)
4. Click **Clear Now**. Restart Firefox.
5. Alternative — surgical strike: `~/Library/Application Support/Firefox/Profiles/*/SiteSecurityServiceState.txt` — delete or grep out any line starting with `aurora.local:HSTS`. Restart Firefox.

### Safari (macOS)
1. Quit Safari.
2. `~/Library/Cookies/HSTS.plist` — delete this file. `rm ~/Library/Cookies/HSTS.plist`.
3. Reopen Safari.

### Chrome / Edge / Brave (any OS)
1. Address bar: `chrome://net-internals/#hsts`
2. Under **Delete domain security policies**, enter `aurora.local`, click **Delete**.
3. Also `sonarr.aurora.local`, `bazarr.aurora.local`, etc. if you visited them before.
4. Close and reopen the browser.

### Install the root CA (green padlock, no warnings)

**macOS:**
```
curl -k -o /tmp/aurora-root.crt https://aurora.local/api/system/caddy-root.crt
sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain /tmp/aurora-root.crt
```

Or GUI: double-click the .crt to open Keychain Access → drag into **System** → double-click the cert → expand **Trust** → set "When using this certificate: **Always Trust**" → close (asks for password).

**iOS/iPadOS:** open `https://aurora.local/api/system/caddy-root.crt` in Safari → tap Allow → Settings → Profile Downloaded → tap the profile → Install → then Settings → General → About → Certificate Trust Settings → toggle ON for the Caddy root CA.

**Windows:** download the .crt, double-click → Install Certificate → Local Machine → Place all certificates in: **Trusted Root Certification Authorities**.

**Android:** Settings → Security → Encryption & credentials → Install a certificate → CA certificate. Some Android versions require the cert be in DER format; use `openssl x509 -in root.crt -out root.der -outform der` first.

After install, https://aurora.local/ shows a green padlock; the same root also validates every future subdomain (`sonarr.aurora.local`, etc.) because Caddy's `tls internal` issues short-lived leaf certs from this root on demand.

---

## 6. Media services — separate breakage (NOT this worker)

Bruce also flagged prowlarr/sonarr/radarr/seerr returning 500 on their `/ping` endpoints. That's not TLS — it's a media-package config or permissions issue (I saw `seerr` crashing with `EACCES: permission denied, mkdir '/app/config/logs/'`). Handing off to whoever picks up media triage; my scope was TLS/networking only.

Quick diagnostic for that worker: `docker logs prowlarr 2>&1 | tail -30`, `ls -la data/prowlarr/`. Likely UID mismatch on the config volume post-Bruce-restart.

---

## 7. Completion-gate impact

- `bash scripts/verify-iter3.sh` → **17/17 green** (unchanged).
- `git log --oneline fd8ea9c..HEAD` → +1 commit (`aurora: TD8 — Caddy TLS on :80+:443 with no-HSTS + healthcheck`).
- `.ralph/running.md` TD8 residual can be marked **shipped** (see task file update).
- No files touched under `packages/dashboard/frontend/` — parallel worker's dark/light audit is safe.

---

## 8. Residual risks / next steps

1. **Cert rolls every 12h** by default with `tls internal`. That's Caddy's design — the intermediate is stable, so leaf-cert rotation is transparent. But if Bruce's dev machine caches certificate errors (rare), it could look like a regression. Fix if ever needed: `tls internal { ca_root ... }` to pin longer-lived leaves.
2. **IPv6 mDNS is answering first** on the box (`getent hosts aurora.local` returns `fdeb:...` before `192.168.0.110`). Not a problem in practice — Caddy binds `[::]:80` too — but if some client is picky it could confuse things. iter-5 could add an avahi service file scoped to IPv4-only.
3. **UDP :443 published** for HTTP/3. Caddy will negotiate H3 when the client supports it; Firefox does, Safari does over TLS 1.3. If it ever misbehaves, drop `"443:443/udp"` from compose.yml.
4. **`AURORA_HOST_PORT=8090` bind** is still published for direct access — kept intentionally as an escape hatch for the "browser fully broken" case Bruce hit. Iter-5 could gate this behind `AURORA_PUBLISH_DIRECT=1` for security, but for a homelab it's fine.

Commit: `<pending>` on `origin/rename/aurora`. Push after the parent confirms no collision with the frontend worker.
