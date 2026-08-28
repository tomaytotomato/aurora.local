# Journey worksheet — full nuke → reinstall → be Sarah (2026-08-28)

What this is: a **worksheet**, not a report. Every item is a checkbox with
evidence, the doctrine it violates, and a concrete fix. It is written to be
ground down one item per commit by the Ralph loop.

Method: the live box was destroyed (all containers, all volumes, `data/`,
`.state.yml`, every `packages/*/.env`, `group_vars/all.yml`, `inventory.ini`),
the dashboard image was rebuilt from `main@a3c6227`, and `bash bootstrap.sh
install` was run from scratch. The browser journey was then walked as **Sarah**
(new admin user `sarah`), through onboarding, the catalogue, two installs
(privacy/AdGuard, jellyfin), and every dashboard page. Screenshots: `/tmp/j*.png`.
Install log: `/tmp/aurora-bootstrap.log`.

Grading lens is `Essence.md`:
1. **Zero terminal** — every CLI affordance in the user journey is a defect.
2. **Honest state** — a number or status on screen is either real or not shown.
3. **One clear choice per job** — opinionated catalogue.
4. **The box just works** — "reachable at aurora.local with a green padlock and
   no `/etc/hosts` editing".

Severity: **blocker** (Sarah is stopped, misled, or locked out) >
**friction** > **polish**.

---

## Scoreboard

| ID | Sev | Title | Done |
|----|-----|-------|------|
| A1 | blocker | LAN detection follows the VPN route; UFW opens the wrong subnet | [x] |
| A2 | blocker | `ansible -K` prompt breaks the documented `curl \| bash` install | [x] |
| A3 | friction | Install log screams about secrets it is about to generate itself | [ ] |
| A4 | friction | Post-install notes are stale and terminal-first | [ ] |
| A5 | polish | Ansible deprecation noise dominates the install transcript | [ ] |
| A6 | friction | No reset/uninstall path anywhere | [ ] |
| A7 | blocker | Published image is stale vs main, and unidentifiable on the box | [ ] |
| B1 | blocker | "AdGuard on this box" does not install AdGuard | [ ] |
| B2 | blocker | AdGuard is never provisioned; the DNS story never completes | [ ] |
| B3 | blocker | Vue escape leak: `${'{'}DOMAIN{'}'}` rendered to the user | [ ] |
| B4 | friction | Wrong step reference ("trust the new TLS root (step 7)") | [ ] |
| B5 | friction | Step 7 (SSO) is silently skipped; 6 and 7 never tick | [ ] |
| B6 | blocker | "Password recovery" is promised in copy, unimplemented in fact | [ ] |
| B7 | friction | Done step hands out `http://` right after the TLS-trust step | [ ] |
| B8 | polish | "Hostname & domain" step never lets you set the hostname | [ ] |
| B9 | polish | Welcome CPU string truncated mid-token | [ ] |
| C1 | blocker | `<service>.aurora.local` does not resolve on Linux/Android clients | [ ] |
| C2 | blocker | 2FA-gated apps are unreachable: enrolment codes land in a server file | [ ] |
| C3 | blocker | Installed AdGuard has no Open link anywhere | [ ] |
| C4 | blocker | App detail dumps the operator README (CLI steps) into Sarah's UI | [ ] |
| C5 | blocker | Config card renders raw `.env` comment art and env var names | [ ] |
| C6 | friction | "UNHEALTHY" badge next to "Enabled and running" | [ ] |
| C7 | friction | "couldn't reach the registry last time it looked" + "Checked never." | [ ] |
| C8 | friction | Every install adds a HIGH finding Aurora itself caused, with no fix | [ ] |
| C9 | friction | Installing one app bounces every other running container | [ ] |
| C10 | friction | `.state.yml` drops `dashboard` after the first in-app install | [ ] |
| C11 | friction | Settings claims it can't read the TLS root; the API serves it fine | [ ] |
| C12 | friction | TLS card: unexpanded `$DOMAIN`, Linux steps miss the browser store | [ ] |
| C13 | polish | "last 24 hours" metrics and 4-day uptime on a 20-minute-old box | [ ] |
| C14 | polish | Activity feeds show raw event keys | [ ] |
| C15 | polish | `/users` heading is unreadable against the hero image | [ ] |
| C16 | polish | Catalogue: no search, three webmails, truncated copy, missing icons | [ ] |
| C17 | polish | "Ask whoever set up this box" — Sarah *is* that person | [ ] |
| D1 | polish | `Essence.md` is unreferenced and inconsistently named | [ ] |
| D2 | polish | README package table lists 12 of 18 packages | [ ] |

---

## A · Phase 1 install (`bootstrap.sh` + Ansible)

### A1 · [blocker] LAN detection follows the VPN route
`_detect_lan_ip` uses `ip route get 1.1.1.1`, and `_detect_lan_cidr` takes the
first `proto kernel` route. On this box (ProtonVPN up, which is a *normal*
state for a privacy-minded homelab — Aurora itself ships Gluetun) that yielded:

```
lan_ip=10.2.0.2 lan_cidr=100.85.0.0/24     # bootstrap.sh
firewall_lan_cidr: 100.85.0.0/24           # group_vars/all.yml
```

while the real LAN is `192.168.0.110/24` on `enp0s31f6` — which the dashboard's
own detector got right (Welcome step showed `LAN IP 192.168.0.110`). UFW is then
opened to the VPN subnet and (on a genuinely fresh box) **not** to the LAN, so
the box becomes unreachable from Sarah's laptop. AdGuard would also bind :53 to
the tunnel address.

**Fix:** pick the interface that owns a default route *and* an RFC1918 address,
skipping `tun*/wg*/proton*/tailscale*/ppp*` device names; prefer the interface
with the lowest-metric non-VPN default route. Reuse (or share) whatever
`SystemService` does, since it is already correct. Add a unit test with the
`ip route` output captured above. Files: `bootstrap.sh`.

**SHIPPED:** `scripts/lib/net.sh` picks a LAN *interface* instead of following a
route — name filter (lo/docker/br-/veth/tun/wg/proton/pvpn/tailscale/zt/ppp),
RFC1918-only address filter (so CGNAT 100.64/10 is out), `/31`+`/32` rejected as
point-to-point, default-route owners preferred. Returns empty rather than
guessing, and `bootstrap.sh` now says so in plain English before falling back.
11 fixture tests in `scripts/tests/net.test.sh` (the ProtonVPN box above,
Tailscale, `/22`, no-default-route, nothing-usable), wired into `ci.yml` and
`scripts/verify.sh`. Live box re-derived to `192.168.0.110` / `192.168.0.0/24`
and its ufw rules rebuilt — the VPN subnet is no longer trusted.

### A2 · [blocker] `ansible -K` prompt breaks `curl | bash`
`_run_host_bootstrap` runs `ansible-playbook … -K`. In the documented one-liner
(`curl -fsSL … | bash`) stdin is the pipe, so the prompt reads garbage/EOF:

```
/usr/lib/python3.14/getpass.py:99: GetPassWarning: Can not control echo on the terminal.
Warning: Password input may be echoed.
BECOME password:
```

It only survived here because this box has NOPASSWD sudo. On a stock Debian box
the run dies at the first `become` task.

**Fix:** probe `sudo -n true` first; if passwordless, drop `-K`. Otherwise read
the password from `/dev/tty` with plain-English copy ("Your Linux password, so
Aurora can install Docker and set the firewall"), and fail with that sentence if
there is no tty. Files: `bootstrap.sh`.

**SHIPPED:** `_run_host_bootstrap` now has three honest cases — passwordless
sudo never prompts; with a terminal it prompts on `/dev/tty` (so the curl pipe on
stdin is irrelevant); with neither it stops *before* touching the host with
"Aurora needs your login password to set this box up, and there is no terminal to
ask on. Run it from a terminal: bash bootstrap.sh". The prompt is introduced by
"Aurora needs your login password once, to install Docker and set up the
firewall" instead of a bare `BECOME password:`.

### A3 · [friction] The install log screams about secrets it then generates
`up.sh` seeds `.env` files, prints 13 `WARN …=<empty>` lines plus

```
WARN IMPORTANT: replace the example password hash in .../users_database.yml
WARN generate one with:  docker run --rm authelia/authelia:latest \
WARN                        authelia crypto hash generate argon2 --password 'yourpass'
```

…and then immediately rotates all 13 automatically. The scary block is about
work Aurora is doing for you, and the `docker run` line is a terminal
instruction for something the dashboard now owns (the admin user is created in
the wizard).

**Fix:** run `rotate-secrets.sh --apply` first, then warn only about what is
still weak. Delete the `authelia crypto hash` advice. Files: `scripts/up.sh`,
`scripts/lib/render.sh`, `scripts/rotate-secrets.sh`.

### A4 · [friction] Post-install notes are stale and terminal-first
`core`'s `post_install_notes` still walks the operator through the **removed**
Stalwart setup wizard ("Stalwart boots in bootstrap mode… complete it once…
choose SQLite + filesystem blobs"), tells them to set `STALWART_ADMIN_SECRET` in
`packages/core/.env`, prints `https://mail-admin.$DOMAIN/` with `$DOMAIN`
uninterpolated, and points at `./scripts/get-caddy-root-cert.sh` although the
dashboard now has a Download button (commit a3c6227).

**Fix:** rewrite `packages/core/manifest.yml` notes to today's truth (mail is
auto-provisioned; the recovery admin is revealed in the dashboard), interpolate
`$DOMAIN`/`$LAN_IP` before printing, and replace every script reference with the
screen that does the job.

### A5 · [polish] Ansible deprecation noise
Five `INJECT_FACTS_AS_VARS` blocks with source excerpts dominate the transcript.
**Fix:** `deprecation_warnings = False` in `ansible.cfg` and move host roles to
`ansible_facts['os_family']`.

### A6 · [friction] There is no reset path
Nothing in `bootstrap.sh`, `scripts/`, or the dashboard takes a box back to
clean. Nuking it required hand-rolled `docker rm -f`, `docker volume prune`,
`sudo rm -rf data/`, and deleting `.state.yml`/`.env`s. A consumer box must be
resettable by the consumer.

**Fix:** `bootstrap.sh reset [--keep-data]` (stop everything, drop volumes,
clear runtime state, keep the repo) plus Settings → "Start over" with a typed
confirmation, streaming through the existing job/SSE plumbing.

### A7 · [blocker] Published image is stale, and the box can't say what it runs
`ghcr.io/tomaytotomato/aurora:0.1.0` on GHCR carries
`org.opencontainers.image.revision=3232c95` (2026-08-26) while `main` is
`a3c6227` (2026-08-28) — the tag is mutable in name only, so a fresh
`curl | bash` install silently gets a two-day-old dashboard, and nothing in the
UI reveals which build is running. (This journey had to rebuild locally to test
main at all.)

**Fix:** publish on merge to main (rolling `:main` plus immutable
`:sha-<short>`); surface `image.revision` + build date in Settings → System;
make "Check and update" compare that revision, not just the tag.

---

## B · Onboarding wizard

### B1 · [blocker] "AdGuard on this box" does not install AdGuard
Step 4 (`DNS story`, default tab) promises: *"Install the privacy package
(AdGuard Home). Seed a rewrite for `*.aurora.local` → this box's LAN IP."*
Step 6 then lists `Packages: Core` only and warns:

> DNS mode is 'adguard' but the privacy package (which provides AdGuard Home) is not selected.

There is no package picker in the wizard and no "add it" affordance in the
warning, so the only exit is Install-anyway. The wizard promised something, then
told the user it isn't going to happen, then did it anyway.

**Fix:** when `dns=adguard`, add `privacy` to the install plan (chip visible on
the review step) and delete the warning; keep the warning only as a
one-click "Add AdGuard" if the plan is ever edited by hand.

### B2 · [blocker] AdGuard is never provisioned; the DNS story never completes
Even installing `privacy` by hand from the catalogue leaves:

```
$ ls data/adguard/conf/        → empty
$ dig @192.168.0.110 test.aurora.local   → connection refused (:53 not serving)
$ curl -I http://192.168.0.110:3000/     → 302 /install.html   (setup wizard)
```

`seed-adguard.sh` only works *after* a human completes AdGuard's own web wizard,
which nothing in the journey tells Sarah to do. So the chosen DNS story silently
does not exist, and if she does point her router at the box, the whole LAN loses
DNS.

**Fix:** provision AdGuard on install — write `AdGuardHome.yaml` (admin user =
the Aurora admin, bcrypt hash, bind `0.0.0.0:53`, upstreams, and the
`*.$DOMAIN → LAN_IP` rewrite) before first start, exactly as core now does for
Stalwart/Authelia. Verify with `dig @<lan-ip> anything.aurora.local`. Then add a
Done-step check "DNS answers for *.aurora.local" that is real, not assumed.

### B3 · [blocker] Vue escape leak in step 3
Step 3 renders literally:

> Every `.env` that references `${'{'}DOMAIN{'}'}` re-renders.

Two defects in one line: a broken template escape shown to the user, and a
sentence about `.env` files. **Fix:** rewrite in Sarah's language ("Every app
that points at this domain is rebuilt with the new name") and delete the escape.
File: `frontend/src/views/onboarding/OnboardingDomain.vue`.

### B4 · [friction] Wrong step reference
Same card: *"You'll need to trust the new TLS root (step 7)."* Trust the root CA
is **step 5**; step 7 is SSO. **Fix:** derive the number from the step list, or
link the step instead of numbering it.

### B5 · [friction] Step 7 is silently skipped and steps never tick
Clicking Install jumps from 6 → 8 ("You're set", *Step 8 of 8*) while the
sidebar leaves **6 Review & install** and **7 Set up SSO** unchecked. Either SSO
setup is dead weight in the stepper or it was skipped by accident; both read as
"something went wrong".

**Fix:** if SSO needs no input, remove the step (7 steps, honest); otherwise run
it. Mark every completed step complete when the wizard advances.

### B6 · [blocker] Password recovery is promised, then denied
Step 2 body copy: *"If you lose the password, use the password recovery option
on this screen to reset it."* Clicking it opens:

> Password recovery is coming to the dashboard shortly. In the meantime, if
> you've lost the admin password, ask whoever set this box up to reset it for you.

Sarah is the person who set the box up. The only real recovery is
`scripts/reset-admin-password.sh` over SSH — the exact thing the doctrine bans.

**Fix (pick one, in order of preference):** (a) implement recovery — a one-time
recovery code shown at account creation, stored hashed, redeemable at
`/login`; (b) until then, tell the truth on the card itself and print the
recovery code with the password. Do not advertise a control that opens an
apology.

### B7 · [friction] Done hands out `http://` after the TLS step
The Done step's "REACH THIS BOX AT" lists `http://aurora.local` and
`http://192.168.0.110` — three screens after asking the user to install a root
CA "so your browser stops warning". **Fix:** lead with
`https://aurora.local`, keep the LAN-IP `http://` as the labelled fallback, and
say which one to bookmark.

### B8 · [polish] The "Hostname & domain" step has no hostname
Sidebar says "Hostname & domain"; the page is titled "Pick your domain" and only
has a domain field. **Fix:** rename the step to "Domain", or let the hostname be
edited there.

### B9 · [polish] Truncated CPU string on Welcome
`Intel(R) Core(T…` — one line, hard-truncated. **Fix:** normalise the model
string (strip `(R)`, `(TM)`, `CPU @ …`) and let it wrap to two lines.

---

## C · Dashboard and the app journey

### C1 · [blocker] `<service>.aurora.local` does not resolve on Linux/Android
The single biggest promise ("all reachable at `aurora.local` … no `/etc/hosts`
editing") fails for multi-label names on the most common resolver stack:

```
$ getent hosts jellyfin.aurora.local    → (nothing, rc=2)
$ ping jellyfin.aurora.local            → Name or service not known
$ avahi-resolve -n jellyfin.aurora.local → 192.168.0.110   (works)
$ hosts: files mdns4_minimal [NOTFOUND=return] dns   ← /etc/nsswitch.conf
```

`mdns4_minimal` resolves only single-label `*.local`, so every "Open <app>" link
Aurora renders is a dead end on Linux clients (and Android has no equivalent
resolver at all); Chromium on this very box returned `ERR_NAME_NOT_RESOLVED`.
macOS/iOS Bonjour is the happy path. Aurora's Settings card states the opposite:
*"so other devices on your network can reach `<label>.aurora.local` with no
setup on those devices."* Additionally `aurora.local` itself resolves to the
**docker bridge** address (`172.18.0.1`) because avahi publishes on every
interface, which will hand LAN clients an unroutable A record.

**Fix (three parts, can be separate commits):**
1. Restrict avahi to the LAN interface (`allow-interfaces=<lan-if>`,
   `deny-interfaces=docker0,br-*`) in the host role, so `aurora.local` cannot
   resolve to a bridge IP.
2. Make the AdGuard path real (B2) and, on the Done step + Settings, state
   per-platform truth: works on Apple devices out of the box; Linux/Android
   need the box as their DNS server.
3. Every Open CTA gets a `http://<lan-ip>:<port>` fallback link, which never
   depends on name resolution.

### C2 · [blocker] Anything behind SSO is unreachable without a terminal
`packages/core/authelia/configuration.yml` sets `policy: two_factor` for
`*.$DOMAIN` (and for `mail-admin`), while the notifier is

```yaml
notifier:
  filesystem:
    filename: /data/notification.txt
```

so the 2FA enrolment link/code lands in `data/authelia/notification.txt` on the
server. Verified live: `POST /api/firstfactor` as `sarah` succeeds
(`authentication_level: 1`), and `https://mail-admin.aurora.local/` still
bounces back to the portal because it needs level 2. Sarah cannot enrol without
SSH. Settings compounds it: *"Passkey sign-in isn't wired up on this box yet."*

**Fix:** surface Authelia's pending notification in the dashboard (an
"Aurora needs to verify it's you — here's your code" panel, admin-only, with the
file as the source), **and/or** point the notifier at the core Stalwart SMTP now
that every Aurora user gets a mailbox, **and** add a "Set up your second factor"
card to the Done step / Overview attention strip so enrolment happens before the
first gated app is installed.

### C3 · [blocker] Installed AdGuard has nowhere to click
After installing `privacy`, the detail page shows `vhosts: none`, no Open CTA,
no address anywhere in the UI; AdGuard is on `<lan-ip>:3000`. LAN aliases lists
only `auth`, `mail-admin`, `jellyfin`.

**Fix:** ship `packages/privacy/caddy.snippet` for `adguard.$DOMAIN` (and an
mDNS alias), or — better, generally — render an Open link from the manifest's
published port whenever a package has no vhost.

### C4 · [blocker] The operator README is rendered into Sarah's UI
`/apps/privacy` shows, verbatim, under "What this is":

> **First-run** 1. Copy `.env.example` to `.env`; pick a VPN provider and paste
> creds. 2. `./scripts/up.sh privacy` 3. Visit `http://<lan-ip>:3000` for
> AdGuard's setup wizard. 4. `./scripts/seed-adguard.sh` seeds the
> `*.aurora.local` DNS rewrites.

`/apps/jellyfin` adds hardware-transcoding compose edits (`uncomment the
devices: and group_add: block`), a "Why not behind Authelia?" essay, and a raw
env block. This is the single largest concentration of terminal jargon in the
product, and it sits on the page users visit most.

**Fix:** the package contract gains a user-facing block (`manifest.yml: about:`
+ optional `user_notes:`) that the detail page renders by default; the README
moves behind a collapsed "For the owner (advanced)" disclosure. Update
`docs/PACKAGE_CONTRACT.md` and every package.

### C5 · [blocker] Config card renders `.env` comment art
The "What you'll be asked to set" table shows `VPN_SERVICE_PROVIDER`,
`WIREGUARD_PRIVATE_KEY`, `FIREWALL_OUTBOUND_SUBNETS`, each with the raw comment
text from `.env.example`, including `---- gluetun: provider selection
--------------------` rules. Two entries are badged **REQUIRED** but install
never asks for them (and privacy installs fine without them, because Gluetun is
profile-gated).

**Fix:** render label/help from manifest-declared fields (human label, one-line
help, whether it is genuinely required *for the selected profile*), never raw
`.env` comments. Hide the whole card when nothing is required.

### C6 · [friction] "UNHEALTHY" next to "Enabled and running"
Right after a successful install, the privacy page header showed a red
**UNHEALTHY** badge while the control row said "Enabled and running." AdGuard
simply has no `HEALTHCHECK`. **Fix:** map "no healthcheck" → running (with a
neutral "health not reported" tooltip); reserve unhealthy for real failures.

### C7 · [friction] Version card contradicts itself
> Version unknown · UNCHECKED — "Aurora couldn't reach the image registry last
> time it looked, so this is the version on the box rather than the newest one
> available." … "Checked never."

**Fix:** three honest states — never checked / checked at <time> / check failed
at <time> — and only show the failure sentence for the third.

### C8 · [friction] Aurora's own images generate HIGH findings, with no fix
A 20-minute-old box shows 7 findings, 3 HIGH, all "Container X is not
digest-pinned … Pin the compose file to a specific `@sha256:…` digest", for
images Aurora ships (`adguard`, `authelia`, `jellyfin`). The count grows with
every install, the page claims "Every finding has a fix — no silent nags", and
the only affordances are a snooze dropdown and **Dismiss**. `scripts/pin.sh`
exists and could fix all of them.

**Fix:** ship digests (or run `pin.sh --apply` as part of enable) so the finding
is absent by default; where it remains, give it a real "Pin these now" button
that runs the existing script through the job runner. Failing that, it is not a
HIGH.

### C9 · [friction] Installing one app restarts the others
Enabling `privacy` recreated `stalwart` (mail down mid-install); enabling
`jellyfin` recreated `adguard` (DNS down). `up.sh` runs `up -d --remove-orphans`
across the whole merged project. **Fix:** pass the newly-enabled package's
service names explicitly, keeping `--remove-orphans` semantics for the
compose-file set only.

### C10 · [friction] `.state.yml` drops the dashboard
After the first in-app install: `enabled: [core, privacy]` — `dashboard` is
gone, which is why `up.sh` carries a 40-line "dashboard orphan guard". Fix the
writer (`state_set_enabled` call site in the in-container path) to preserve
packages it did not touch, then simplify the guard.

### C11 · [friction] Settings claims it cannot read the TLS root
The TLS root CA card renders a red **"Aurora couldn't read the TLS root
certificate just now."** while, at the same moment,
`curl http://aurora.local/api/system/caddy-root.crt` returns a valid
`CN=Caddy Local Authority - 2026 ECC Root` certificate (631 bytes). The one card
whose job is to stop browser warnings says it is broken when it is not.

**Fix:** find the divergent probe (likely a host-path read vs the API/docker
exec path used by the download endpoint), make the card use the same source as
the Download button, and add a regression test for "cert present → no error".

### C12 · [friction] TLS card copy
*"Install this on every device that connects to `*.$DOMAIN`"* — unexpanded
variable, second occurrence in the product (see B3). The Linux instructions are
a `sudo cp … && sudo update-ca-certificates` one-liner, which does not cover
Chrome/Chromium (NSS store) — Firefox gets its own paragraph, Chromium is left
failing with `ERR_CERT_AUTHORITY_INVALID` (reproduced).

**Fix:** interpolate the domain; add a Chromium/NSS paragraph
(`certutil -d sql:$HOME/.pki/nssdb …`) or, better, ship a one-click
"trust this cert" download for Linux desktops.

### C13 · [polish] Time-travelling metrics
Overview shows "CPU last 24h 30.4%" and a chart labelled "Host CPU % · last 24
hours" on a box that has existed for 20 minutes, plus "uptime 4d 22h" (host
uptime) on a fresh install. **Fix:** label the window by what exists
("since install", "last 40 minutes"), and label host uptime as host uptime.

### C14 · [polish] Raw event keys in activity feeds
`mdns.alias.publish`, `job.finish`, `enable:jellyfin`,
`stalwart.secrets.bootstrap`, `health:healthy stalwart`. **Fix:** a
key → sentence map ("Published jellyfin.aurora.local on the network"), with the
raw key behind a details toggle.

### C15 · [polish] `/users` heading contrast
The "Users" H1 and its "ACCESS" eyebrow sit on the dark aurora hero and are
close to unreadable. **Fix:** the same scrim/offset treatment the other pages
use.

### C16 · [polish] Catalogue
No search or filter across 17 apps; **three** webmail clients (Bulwark,
Roundcube, SnappyMail) and two note apps (Memos, SilverBullet) contradict "one
clear choice per job"; card descriptions truncate mid-word ("Runs on CPU by
default; opt-in NVIDI…"); SnappyMail and SilverBullet render blank/letter icons.
**Fix:** add search + outcome chips ("Watch", "Block ads", "Photos"), pick a
default webmail and mark the others "alternative", clamp descriptions on a word
boundary, fix the two icons.

### C17 · [polish] Wrong-audience copy
Settings → App marketplace: *"Ask whoever set up this box to turn it on."* Same
pattern as B6. **Fix:** sweep the frontend for "ask whoever" / "whoever set this
box up" and address the reader as the owner.

---

## D · Repo and docs

### D1 · [polish] `Essence.md` is orphaned
The file is `Essence.md` at the repo root (mixed case), referenced from no
README, no docs index, and no CONTRIBUTING. **Fix:** rename to `ESSENCE.md`,
link it from the top of `README.md` and `docs/ARCHITECTURE.md` as the document
that outranks the rest.

### D2 · [polish] README package table is stale
It lists 12 packages; the repo ships 18 (missing `bulwark`, `filebrowser`,
`jellyfin`, `memos`, `roundcube`, `snappymail`), and the catalogue shows 17.
**Fix:** generate the table from the manifests in CI, or check it in CI.

---

## Working rules for the loop

- One item per commit. Message: `fix(<area>): <title>` referencing the ID.
- Tick the box in the scoreboard **in the same commit** as the fix.
- Gates before every commit: backend `mvn -q test`, `vue-tsc --noEmit`,
  `npm run test:unit`, plus `shellcheck` for shell changes.
- Anything that changes the install path must be re-verified against the live
  box (rebuild image → `docker compose up -d` → walk the affected screen).
- Product-judgement forks (which webmail is the default; whether SSO stays a
  wizard step) get written into the item and left for the owner rather than
  guessed.
