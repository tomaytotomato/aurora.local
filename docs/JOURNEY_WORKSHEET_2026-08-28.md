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
| A3 | friction | Install log screams about secrets it is about to generate itself | [x] |
| A4 | friction | Post-install notes are stale and terminal-first | [x] |
| A5 | polish | Ansible deprecation noise dominates the install transcript | [x] |
| A6 | friction | No reset/uninstall path anywhere | [x-cli] |
| A7 | blocker | Published image is stale vs main, and unidentifiable on the box | [ ] |
| A8 | friction | "Start over" exists only as a script, not in the dashboard | [ ] |
| B1 | blocker | "AdGuard on this box" does not install AdGuard | [x] |
| B2 | blocker | AdGuard is never provisioned; the DNS story never completes | [x] |
| B3 | blocker | Vue escape leak: `${'{'}DOMAIN{'}'}` rendered to the user | [x] |
| B4 | friction | Wrong step reference ("trust the new TLS root (step 7)") | [x] |
| B5 | friction | Step 7 (SSO) is silently skipped; 6 and 7 never tick | [x] |
| B6 | blocker | "Password recovery" is promised in copy, unimplemented in fact | [x] |
| B7 | friction | Done step hands out `http://` right after the TLS-trust step | [x] |
| B8 | polish | "Hostname & domain" step never lets you set the hostname | [x] |
| B9 | polish | Welcome CPU string truncated mid-token | [x] |
| C1 | blocker | `<service>.aurora.local` does not resolve on Linux/Android clients | [ ] |
| C2 | blocker | 2FA-gated apps are unreachable: enrolment codes land in a server file | [ ] |
| C3 | blocker | Installed AdGuard has no Open link anywhere | [x] |
| C4 | blocker | App detail dumps the operator README (CLI steps) into Sarah's UI | [x] |
| C5 | blocker | Config card renders raw `.env` comment art and env var names | [x] |
| C6 | friction | "UNHEALTHY" badge next to "Enabled and running" | [x] |
| C7 | friction | "couldn't reach the registry last time it looked" + "Checked never." | [x] |
| C8 | friction | Every install adds a HIGH finding Aurora itself caused, with no fix | [x] |
| C9 | friction | Installing one app bounces every other running container | [x] |
| C10 | friction | `.state.yml` drops `dashboard` after the first in-app install | [x] |
| C11 | friction | Settings claims it can't read the TLS root; the API serves it fine | [x] |
| C12 | friction | TLS card: unexpanded `$DOMAIN`, Linux steps miss the browser store | [~] |
| C13 | polish | "last 24 hours" metrics and 4-day uptime on a 20-minute-old box | [x] |
| C14 | polish | Activity feeds show raw event keys | [x] |
| C15 | polish | `/users` heading is unreadable against the hero image | [x] |
| C16 | polish | Catalogue: no search, three webmails, truncated copy, missing icons | [ ] |
| C17 | polish | "Ask whoever set up this box" — Sarah *is* that person | [x] |
| C18 | friction | Manifest descriptions still written for operators (found while fixing C4) | [ ] |
| C19 | polish | Review lists a vhost for a profile-gated service that will not start | [x] |
| C22 | blocker | Review listed the doubled `aurora.aurora.local` (regression from C10) | [x] |
| C20 | friction | The SSO step links to auth.$DOMAIN before the DNS that resolves it is running | [ ] |
| C21 | fork | Ship image digests, or a "Pin these now" action (owner's call) | [ ] |
| D1 | polish | `Essence.md` is unreferenced and inconsistently named | [x] |
| D2 | polish | README package table lists 12 of 18 packages | [x] |

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

**SHIPPED:** `rotate-secrets.sh --apply` no longer prints a WARN per key — on a
fresh install every secret is legitimately empty and the script fixes all of them
seconds later, so it now says `core/.env — generating 13 missing secret(s)` and
`OK generated 13 secret(s) for core`. Report mode (the operator audit) still
lists every offending key. `render_authelia_seed` drops the `IMPORTANT: replace
the example password hash` + `docker run ... crypto hash generate argon2` block
for "placeholder sign-in file written; the wizard replaces it with your admin
account", which is what actually happens.

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

**SHIPPED:** both core and dashboard notes rewritten — the whole block is now
four lines pointing at the browser ("Open it and finish setup in the browser:
http://aurora.local/ or http://192.168.0.110/ ... Nothing left to do at a
terminal"), the removed Stalwart wizard is replaced by what actually happens
(mail is pre-configured, recovery admin is in the dashboard), and `bootstrap.sh`
interpolates `$DOMAIN`/`$HOSTNAME`/`$LAN_IP` before printing, so the last thing
the installer prints is an address that can actually be typed.

### A5 · [polish] Ansible deprecation noise
Five `INJECT_FACTS_AS_VARS` blocks with source excerpts dominate the transcript.
**Fix:** `deprecation_warnings = False` in `ansible.cfg` and move host roles to
`ansible_facts['os_family']`.

**SHIPPED:** both. Every `ansible_os_family` / `ansible_distribution*` /
`ansible_architecture` / `ansible_memtotal_mb` / `ansible_hostname` reference in
`host/roles/` now reads `ansible_facts['...']`, and `ansible.cfg` silences the
notice. A full `--check` run of `host/site.yml` prints zero deprecation lines
(was five multi-line blocks with caret diagrams).

### A6 · [friction] There is no reset path
Nothing in `bootstrap.sh`, `scripts/`, or the dashboard takes a box back to
clean. Nuking it required hand-rolled `docker rm -f`, `docker volume prune`,
`sudo rm -rf data/`, and deleting `.state.yml`/`.env`s. A consumer box must be
resettable by the consumer.

**Fix:** `bootstrap.sh reset [--keep-data]` (stop everything, drop volumes,
clear runtime state, keep the repo) plus Settings → "Start over" with a typed
confirmation, streaming through the existing job/SSE plumbing.

**SHIPPED (half):** `scripts/reset.sh` + `bootstrap.sh reset`, with `--yes`,
`--keep-data` and `--all`. It says what it will do in the same words the UI
would, requires typing RESET (or `--yes`), removes containers/volumes by compose
**label** rather than by compose file so a half-broken or since-removed package
is still cleaned up, deletes `data/` (with sudo only when root-owned files are
actually there), clears `.state.yml` and every `packages/*/.env`, and leaves the
repo, docker and the firewall alone. Deliberately skips the prereq check, because
resetting has to work on a box whose install failed halfway.

**Still open:** the in-dashboard "Start over" button. Tracked as A8 so the CLI
half does not read as done.

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

**SHIPPED:** `OnboardingService` now derives packages from the DNS choice
(`packagesForDnsMode()`): `/plan` includes it, so the chip shows on Review before
anything is written, and `/install` persists it with the line "Added Privacy
(LAN DNS + VPN) because you chose AdGuard for DNS." The contradictory warning is
gone; the only remaining one fires when the build genuinely has no AdGuard
package, and is phrased for the reader ("AdGuard isn't available in this build,
so nothing on this box will answer DNS for *.aurora.local. Point your devices at
your router's DNS instead."). The step's own promise lost its jargon too
("Install AdGuard Home on this box", not "Install the `privacy` package").
4 integration tests in `OnboardingDnsImpliesPackageIntegrationTest`.

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

**SHIPPED:** `AdguardProvisionService` writes `AdGuardHome.yaml` before AdGuard's
first start — DNS on :53, DoH upstreams, rewrites for both `aurora.local` and
`*.aurora.local` at the LAN address, admin = the Aurora admin with the same
password (the bcrypt hash is copied, not a second credential invented). Wired
into both paths that can start it (wizard launch, catalogue install) plus a
startup heal for boxes installed before this existed; it never overwrites an
existing config. `render_data_dirs` in `render.sh` now pre-creates every
`../../data/<dir>` a package bind-mounts, user-owned, because Docker creates
missing bind-mount sources as **root** — which is exactly why the first heal
attempt on the live box failed with AccessDenied. Proved live:
`dig @192.168.0.110 jellyfin.aurora.local` → `192.168.0.110`, upstream lookups
resolve, and `POST /control/login` as `sarah` with her Aurora password → 200.
Still open as its own item: a Done-step check that *shows* DNS answering.

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

**SHIPPED:** the line now names the step from `STEP_LABELS` ("on the *Trust the
root CA* step") instead of hardcoding an index that was already two out of date.

### B5 · [friction] Step 7 is silently skipped and steps never tick
Clicking Install jumps from 6 → 8 ("You're set", *Step 8 of 8*) while the
sidebar leaves **6 Review & install** and **7 Set up SSO** unchecked. Either SSO
setup is dead weight in the stepper or it was skipped by accident; both read as
"something went wrong".

**Fix:** if SSO needs no input, remove the step (7 steps, honest); otherwise run
it. Mark every completed step complete when the wizard advances.

**SHIPPED:** `OnboardingReview.install()` hardcoded `router.push('/onboarding/done')`.
It now advances to whatever follows Review in `STEPS` (derived, not hardcoded)
and marks Review complete, so the SSO step is reached and the sidebar ticks. This
is also most of C2: that step is the only thing standing between a fresh box and
"every gated app is impossible to open" — see below.

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

**SHIPPED (a).** `RecoveryCodeService` issues a six-word code (from the password
generator's curated wordlist) when the admin account is created, stores only its
bcrypt hash, and returns the plaintext exactly once. The wizard now stops on that
screen and makes the operator acknowledge it, next to the password they are
already saving. `/login` grew a "Forgot your password?" form — username, code,
new password — which sets the password and immediately issues a replacement code,
so the box is never left without a way back in; the spent code dies instantly. A
wrong username and a wrong code return the same 401, so it cannot enumerate
accounts. 9 service tests, OpenAPI updated. The apology dialog is deleted.

**Also:** every other "ask whoever set up this box" was rewritten (C17) — the
disks parity empty state and the marketplace card now tell the reader what they
can do themselves.

### B7 · [friction] Done hands out `http://` after the TLS step
The Done step's "REACH THIS BOX AT" lists `http://aurora.local` and
`http://192.168.0.110` — three screens after asking the user to install a root
CA "so your browser stops warning". **Fix:** lead with
`https://aurora.local`, keep the LAN-IP `http://` as the labelled fallback, and
say which one to bookmark.

**SHIPPED:** `ReachInfo` takes a `scheme` prop; Done passes `https` for the name
and keeps `http` for the IP (the certificate covers the name, not the address, so
an https IP link would produce the very warning the trust step exists to avoid).
The help text now says which to bookmark and tells the per-platform truth.
Done also gained the router-DNS card the DNS step promises — "set your router's
DNS server to 192.168.0.110" with where to find it and what happens if you skip
it — which was an unkept promise on the last screen of the wizard.

### B8 · [polish] The "Hostname & domain" step has no hostname
Sidebar says "Hostname & domain"; the page is titled "Pick your domain" and only
has a domain field. **Fix:** rename the step to "Domain", or let the hostname be
edited there.

**SHIPPED:** renamed to "Your domain". The hostname is a host-level fact
(`hostname -s`, set before Aurora exists) and the wizard has no business
pretending otherwise.

### B9 · [polish] Truncated CPU string on Welcome
`Intel(R) Core(T…` — one line, hard-truncated. **Fix:** normalise the model
string (strip `(R)`, `(TM)`, `CPU @ …`) and let it wrap to two lines.

**SHIPPED:** exactly that — "Intel(R) Core(TM) i5-6500T CPU @ 2.50GHz" becomes
"Intel Core i5-6500T", and the line is allowed to wrap instead of truncating the
part that identifies the chip.

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

**SHIPPED:** `packages/privacy/caddy.snippet` (adguard.$DOMAIN, http + https,
deliberately not behind Authelia). While doing it, the root cause turned up:
`packages/core/caddy/Caddyfile` hardcoded vhosts for adguard **and** the whole
media stack, so Caddy advertised addresses for apps that were not installed while
the dashboard — which reads per-package snippets — showed `vhosts: none`, and
adding the snippet collided outright ("ambiguous site definition:
https://adguard.aurora.local"). Those blocks moved to
`packages/media/caddy.snippet` and the privacy one, leaving core with only its
own vhosts. Verified live: `https://adguard.aurora.local` → AdGuard's login,
`/api/packages/privacy` reports `vhosts: [adguard.aurora.local]`, and the app
page now renders the Open CTA. The generic "no vhost → offer host:port" fallback
is still worth doing for backend-only apps; left for its own item.

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

**SHIPPED (simpler than the plan):** no new manifest field was needed — the
manifest already carries a `description`, written for the person deciding whether
they want the app. About now renders that, and the README moves into a closed
`Setup notes for the owner · technical` disclosure on both the pre-install
preview and the installed page. Nothing is deleted; it just stops being the first
thing a non-technical owner reads. The `privacy` and `jellyfin` descriptions were
rewritten to carry that weight ("Your own Netflix, for the films, TV and music on
this box"); the rest are follow-up C18.

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

**SHIPPED:** `lib/envCopy.ts` (`humanEnvLabel`, `cleanEnvHelp`, 8 tests) turns
`WIREGUARD_PRIVATE_KEY` into "Wireguard private key" and strips the divider art
(`---- gluetun: provider selection ----`) plus everything after the first
sentence. The pre-install card now lists only genuinely required values under
"What you'll need to hand", with "Aurora fills in everything else"; the installed
Config tab uses the same two helpers, so the two surfaces cannot drift.

### C6 · [friction] "UNHEALTHY" next to "Enabled and running"
Right after a successful install, the privacy page header showed a red
**UNHEALTHY** badge while the control row said "Enabled and running." AdGuard
simply has no `HEALTHCHECK`. **Fix:** map "no healthcheck" → running (with a
neutral "health not reported" tooltip); reserve unhealthy for real failures.

**SHIPPED (root cause was different, and worse):** the red badge came from the
probe's `needs-config` state — AdGuard's first-run detector firing because
nothing had ever configured it (B2). B2 removes the cause; this removes the
mislabel. `needs-config` now maps to its own amber `Needs setup` light instead of
the red `Unhealthy` one, because "waiting for a human inside that app" and "this
app is broken" are not the same sentence.

### C7 · [friction] Version card contradicts itself
> Version unknown · UNCHECKED — "Aurora couldn't reach the image registry last
> time it looked, so this is the version on the box rather than the newest one
> available." … "Checked never."

**Fix:** three honest states — never checked / checked at <time> / check failed
at <time> — and only show the failure sentence for the third.

**SHIPPED:** exactly that. A never-checked app now reads "Aurora hasn't checked
for a newer version yet ... It checks on its own schedule, or you can use Check
and update above" over "Not checked yet.", and the registry-unreachable sentence
only appears when there really was a check.

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

**SHIPPED (the "failing that" branch, deliberately):** severity is now about what
the owner should do — `:latest` is medium, a floating tag is low, and neither is
high, because the box was born in this state, nobody did anything wrong, and no
action exists for a person who has never opened a terminal. The copy is rewritten
for the reader: "Aurora media sonarr updates to whatever version is newest …
Nothing is broken and there is nothing for you to do: Aurora pins these versions
in its own releases, and this entry disappears when it does." A test asserts the
description never tells a household user to pin an `@sha256` digest. The page
header stops claiming "Every finding has a fix — no silent nags" and says which
findings need a person and which Aurora handles. **Left open as a product fork
(C21):** actually shipping digests in the repo, and/or a "Pin these now" button
wired to `scripts/pin.sh --refresh` through the job runner.

### C9 · [friction] Installing one app restarts the others
Enabling `privacy` recreated `stalwart` (mail down mid-install); enabling
`jellyfin` recreated `adguard` (DNS down). `up.sh` runs `up -d --remove-orphans`
across the whole merged project. **Fix:** pass the newly-enabled package's
service names explicitly, keeping `--remove-orphans` semantics for the
compose-file set only.

**SHIPPED (two causes, both worse than compose being noisy):**
1. `rotate-secrets --apply` was "rotating" `WIREGUARD_PRIVATE_KEY` and
   `OPENVPN_PASSWORD` on **every** run — values that belong to the owner's VPN
   provider and that Aurora cannot mint. It wrote 24 random bytes into them,
   which looks configured, destroys the "not set yet" signal, and changes every
   run, so it then recreated the package's containers to "apply" the new junk.
   Keys listed in a manifest's `required_env`, plus an explicit
   external-credential pattern (`HOMEPAGE_VAR_*`, `*_API_KEY`,
   `WIREGUARD_PRIVATE_KEY`, `OPENVPN_PASSWORD`), are now never generated. That
   also stops seven pointless rotations in `core/.env`.
2. `seed-adguard.sh` runs from up.sh's post-up hooks on every launch and
   restarted AdGuard unconditionally — the LAN's DNS server, every time any app
   was installed. It now restarts only when it actually added a rewrite.
   Its fixture also hardcoded `192.168.0.110`/`aurora.local` (one specific box,
   checked into the repo); both are substituted from this box's state, and the
   substitution deliberately does not use `LAN_IP` from `privacy/.env`, where it
   means "bind address" and defaults to `0.0.0.0` — which had just written
   rewrites answering `0.0.0.0` for every name.
Verified: repeated `up.sh` runs now recreate nothing and restart nothing.

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

**SHIPPED (cause was more embarrassing than a divergent probe):** the card
fetches the certificate fine and then hashes it with `crypto.subtle` — which
browsers do not expose on an insecure origin. Reached at `http://aurora.local`,
which is where every new box is reached, the hash threw and the catch rendered
"Aurora couldn't read the TLS root certificate just now". The card that exists to
end browser warnings was reporting itself broken *because* you had not installed
the certificate yet. It now detects the insecure context, keeps the Download
button, and explains that the fingerprint needs https. Regression test included.

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

**SHIPPED:** `lib/eventCopy.ts` with two maps and 5 tests — container events read
"stalwart is healthy", "adguard started", "jellyfin stopped responding"; audit
rows read "Published an address on the network", "Set up the mail server",
"Finished first-run setup". Unknown keys are made readable rather than hidden
(dropping a real event would be the worse failure), and the raw key stays in the
row's `title` for anyone debugging.

### C15 · [polish] `/users` heading contrast
The "Users" H1 and its "ACCESS" eyebrow sit on the dark aurora hero and are
close to unreadable. **Fix:** the same scrim/offset treatment the other pages
use.

**SHIPPED:** the header block was missing the `.on-photo` class every other view
applies over the hero image.

### C16 · [polish] Catalogue
No search or filter across 17 apps; **three** webmail clients (Bulwark,
Roundcube, SnappyMail) and two note apps (Memos, SilverBullet) contradict "one
clear choice per job"; card descriptions truncate mid-word ("Runs on CPU by
default; opt-in NVIDI…"); SnappyMail and SilverBullet render blank/letter icons.
**Fix:** add search + outcome chips ("Watch", "Block ads", "Photos"), pick a
default webmail and mark the others "alternative", clamp descriptions on a word
boundary, fix the two icons.

### C18 · [friction] Manifest descriptions are still written for operators
Now that About renders the manifest `description` instead of the README (C4),
that one paragraph is the only thing most owners will read about an app. Several
are still operator prose: "Prometheus scrapes node_exporter (host) and cAdvisor
(containers)", "Debrid-first (RDTClient) with qBittorrent-behind-gluetun as the
local fallback", "A front end only — the mail server (Stalwart) lives in the core
stack; this connects to it over JMAP". `privacy` and `jellyfin` were rewritten
when C4 landed; the rest have not been.

**Fix:** one pass over every `packages/*/manifest.yml` description: what it does
for the household, in two or three sentences, no component names unless the
owner would recognise them.

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
