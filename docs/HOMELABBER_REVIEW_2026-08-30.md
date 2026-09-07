# Aurora.local — homelabber first-impression review

**Reviewer:** external homelabber persona, headless Chrome only, no source-code peek before starting.
**Date:** 2026-08-30
**Method:** `bash scripts/reset.sh --yes` back to a fresh clone, `rm group_vars/all.yml inventory.ini`, then `bash bootstrap.sh` and walk the browser wizard end-to-end.
**Image under test:** `ghcr.io/tomaytotomato/aurora:0.1.0` (shipped, not a dev branch).
**Host:** Intel i5-6500T, 15.5 GB RAM, no GPU, Ubuntu 26.04 LTS, Docker 29.6.2.
**Screenshots:** `/tmp/aurora-01-welcome.png` → `/tmp/aurora-19c-settings-mid1.png`.

---

## TL;DR

Aurora nails the *voice* better than any homelab tool I've used, and the install got me from `bash bootstrap.sh` to a working dashboard in **70 seconds**. Then I installed my first app from the catalogue and it took SSO down for the whole box — silently — while the dashboard cheerfully insisted "APPS: ALL RUNNING." **That single bug is the review.**

---

## What genuinely impressed me

### Bootstrap that means it
The README says "one command, no questions asked." That's the truth. `bash bootstrap.sh` → 70 seconds → I'm looking at a first-run wizard. No Ansible-vault dance, no interactive questionnaire, no `.env` scavenger hunt. On a fresh box that's the shortest path to "logged in" I've ever seen.

### Copy that reads like it was written by an adult
Selected greatest hits:
- *"mDNS resolves the apex `aurora.local` only. Subdomains like `sonarr.aurora.local` will not resolve without DNS support."* → *"Workaround: Aurora will generate an `/etc/hosts` snippet you can paste onto each client device. Fine for a laptop or two; painful for a household."* (DNS step)
- *"The address underneath always works, from anything, and never depends on names resolving."* (Done step — pointing at the LAN IP)
- On Security → Hardening: *"None of these has a button, on purpose. Each one is an Ansible run or a script that rewrites files on disk, and a dashboard that quietly rewrites your compose files is a dashboard you cannot reason about."*
- On VPN: *"This is not the outbound VPN that anonymises the media stack; for that, see Privacy → Gluetun."* — one sentence disambiguates the two most-confused concepts in the space.

Whoever writes this copy: don't let them touch anything else. They should write everything.

### The onboarding wizard has taste
Eight steps, an actual visual progress bar, aurora photo backdrop with a real CC credit link (rare humility), *"You can restart onboarding any time from Settings"* at the bottom of every step. Welcome screen greets you with **CPU / RAM / Disks / GPU detection** cards — meaning if I try to install AI later, Aurora already knows this Intel i5-6500T has no GPU. That's not a gimmick; that's product design.

### Security page voice is best-in-class
Six findings out of the box, each with a **plain-English risk explanation**. Best of the lot: *"Adguard follows the moving `latest` tag, so a restart can quietly pick up a different version. Nothing is broken and there is nothing for you to do: Aurora pins these versions in its own releases, and this entry disappears when it does."* You've *told me* what's going to fix it. I've never seen a self-hosted tool do that.

### Small things that add up
- **Recovery-code UX** — six words, "keep it in a password manager or written down at home", checkbox gate, one-shot. No email recovery, no SMS, no theatre.
- **"What installing this changes" modal** before every app install: ports, mDNS name, minimum resources, image.
- **Audit log at bottom of Settings**: `19:09:22 · Chose how DNS works · dns:adguard` — real human-readable events. Ridiculously rare.

---

## The single showstopper

**Installing one app from the catalogue took SSO down for the whole box, and the dashboard didn't notice.**

### Reproduction
1. Fresh install, everything green.
2. Apps → search "notes" → install Memos. Confirm the modal.
3. Wait ~30 s. Memos comes up healthy. mDNS publishes `memos.aurora.local`. Great.
4. In the background, `AutheliaMailProvisionService` decides the SMTP settings on `packages/core/.env` are now ready. Writes them. Triggers an Authelia recreate.
5. Authelia's startup check dials `stalwart:587` (submission).
6. Stalwart has **no listener on :587** — the seeded listeners are 25, 465, 993, 4190, and the internal HTTP ones. Port 587 is *published* by the compose file but nothing is bound inside the container.
7. Authelia logs `dial tcp 172.18.0.5:587: connect: connection refused` → `fatal` → exits code 1 → restart loop.
8. Every subdomain — including AdGuard, which relies on Authelia's forward-auth — returns HTTP 502.
9. **The dashboard still shows a green "● APPS: ALL RUNNING · All 4 running" strip in the header, "Core Running", "Aurora Running", "Privacy Running".** The Security page adds an unrelated Medium ("memos is using core's shared database"), still no mention of the outage.

### Evidence
- `AUTHELIA_NOTIFIER_SMTP_ADDRESS=submission://stalwart:587` in `packages/core/.env` (written by Aurora).
- Live JMAP `x:NetworkListener/get` from inside the `aurora` container returned exactly: `smtp, submissions, imaps, pop3s, sieve, https, http` **at the moment of observation (~19:14)**. No `submission`, no `imap` at that time.
- `docker ps`: `authelia   Restarting (1) 43 seconds ago` while the dashboard header says `● APPS: ALL RUNNING`.
- `curl -k https://auth.aurora.local/` → `HTTP 502`. Ditto every gated vhost.

> **Correction 2026-08-30 (from worker triage during fix):** the static claim “the seeded listeners are `smtp, submissions, imaps, sieve, https, http` — no `submission`, no `imap`” was wrong. `StalwartRegistrySeedService` on `main` does seed all six including `submission(587)` and `imap(143)`. The reconcile just hadn’t run yet at 19:14 because it gates on `StalwartProvisionService` first creating the `aurora.local` domain, which itself waits on JMAP being auth-ready (~90 s after Stalwart cold boot). The seed reconcile then landed at ~19:42:38. The bug is a **consumer-side ordering race**, not missing code: `AutheliaMailProvisionService` writes the SMTP env and triggers Authelia recreate ~25 minutes before the submission listener is bound.

### Fix
> **Fixed 2026-08-30 in `dec76cc`:** `AutheliaMailProvisionService` now gates its `.env` write on a real TCP probe of `stalwart:587` (2 s timeout, injected as a `SubmissionProbe` for testability), not just on the JMAP object existing. Registry-object presence ≠ socket bound — Stalwart reloads its listener table asynchronously and the port can refuse connections for minutes after the object is created. Probe returns false → no env write, no Authelia recreate signal; the scheduled reconcile retries. INFO logged once on transition to blocked; DEBUG on subsequent retries.
>
> Follow-up **1b** covers the seed-schedule side (fast-retry on cold boot) so the window closes sooner even for future consumers of :587/:143.

### Why this is the review, not a footnote
- It happened on my *first app install*, from the shipped `0.1.0` image, with no config changes.
- It's silent. The dashboard is proudly green.
- The tagline is *"one sign-in, one password, every service"* — and the first thing installed from the catalogue killed sign-in.
- Overview and Security pages both fail to notice. **If the top-of-page traffic light lies during a real outage, homelabbers stop trusting it during any outage.** That is the death of a status product.

> **Fixed 2026-08-30 in `90f230c`:** the honesty half. `PackagesService` now computes a `degraded` signal (any package with ≥1 running AND ≥1 restarting/exited/dead/paused container) alongside `running`. Top-bar pill flips to amber `Apps: partly running` when any enabled package is degraded. Apps card renders `Restarting` instead of `Running`. New `RestartLoopRule` emits a MEDIUM Security finding `<container> is restart-looping (N restarts …)`. See roadmap items 2 and 3 below for the split.

---

## Real but smaller bugs

1. **False-positive on Security page.** *"Aurora can't find edge protection on this box any more"* is flagged red — while the parent copy in the same card reads *"for the 0 names that resolve from outside your network."* Zero exposure ≠ risk. Should be N/A or suppressed.
2. **AdGuard image uses `:latest`.** Aurora's own Security page flags this as a Medium finding. Ironically, Aurora is both the source of the finding *and* the source of the `:latest` in `packages/privacy/adguard/`. Ship a pinned digest.
3. **Admin username defaults to `aurora`** on the admin step, not the OS user (`bruce`) that the Welcome screen already knows about. `aurora` as an admin username is confusable with the product itself.
4. **JMAP 401s spam the audit log for ~90 s** after each Stalwart recreate. Not user-visible, but adds noise that looks scarier than it is. Adjust log level or wait for `.reachable()` before firing provision attempts.
5. **Onboarding TLS step skips Android.** Settings → TLS *does* list Android with the correct fingerprint. First-run and steady-state should be aligned.
6. **`.local` doesn't resolve from Chrome on Linux without `libnss-mdns`.** Not Aurora's bug, but the Done screen leads with `https://aurora.local` and demotes `http://192.168.0.110/`. Until the router-DNS step is complete, the IP fallback should be the *primary* bookmark.
7. **Install step has no progress UI.** Review & Install → click Install → next screen appears instantly with no log. Happens to be fast enough, but a stuck Postgres pull would be invisible. The "Start services" screen on the next step has excellent progress with a "Live log (N lines)" toggle — port that pattern back.

---

## Honest constraints, not bugs
- Linux TLS trust is `sudo cp … && sudo update-ca-certificates` + `certutil -d sql:$HOME/.pki/nssdb …`. Contradicts the "99% UI" goal, but there is no other way. Admitting it > pretending.
- Stalwart takes ~90 s after container recreation to be fully JMAP-authable; provisioners retry gracefully. Fine.
- `.env` files are plaintext next to compose files. Flagged as a hardening TODO with an honest explanation. Right call.

---

## Verdict

Aurora is the first self-hosted admin plane where I like the *writing* more than the tool. Every screen has a paragraph that made me nod. The DNS step alone earns a bookmark.

But **I can't recommend it to my brother-in-law yet.** The showstopper — installing an app from the catalogue kills sign-in silently, and the dashboard lies about it — has to go before anyone who won't `docker logs authelia` themselves runs this. Everything about Aurora's positioning ("consumer appliance", "zero terminal", "opinionated by design") assumes the dashboard is telling the truth. Right now the dashboard is a very charming little liar.

---

## Roadmap I'd own if it were mine

Ordered by user-impact:

1. **Fix the submission/imap listener seed in `StalwartRegistrySeedService`** (or repoint Authelia at `submissions://stalwart:465`). Unblocks the whole flow.
2. **Make the "APPS: ALL RUNNING" strip actually watch container restart-loops.** `docker ps` restart count > 0 in the last 60 s = amber, not green. Bubble to Overview + a Security finding.
3. **Surface Authelia-specifically on the Overview.** Without it every gated app is 502. Core status pill should be able to reflect "Core degraded: SSO down" independently of the Postgres/Caddy pieces.
4. **Pin AdGuard image digest** in `packages/privacy/pins.env` so Aurora stops flagging its own product.
5. **Kill the "edge protection missing" false positive** when external names = 0.

Do those five and Aurora becomes something I'd give a non-tinkerer.
