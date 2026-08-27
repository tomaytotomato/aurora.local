# Unified Auth — SSO, Passkeys, and Aurora's Login

Status: **REVISED v2 — reviewed, awaiting approval to implement**
Author: iter-auth-unify
Date: 2026-08-27

---

## 1. Where we actually are

Recon, not assumption. Verified against the running box on `main` @ `039c382`.

| Concern | Today |
|---|---|
| User store of record | Aurora SQLite, `AdminUser(id, username, passwordHash, tz, createdAt, role)` |
| Roles | `ADMIN` / `USER` / `GUEST`, cascading → Authelia groups `admins ⊃ users ⊃ guests` |
| Password hash | BCrypt cost 12, Spring Security pure-Java |
| Authelia users | **Projected** from Aurora SQLite by `AutheliaService` → `data/authelia/users_database.yml`, regenerated on every user change + 5-minute drift guard |
| Aurora session | Own cookie via `SessionService`; `/api/auth/login`, `/logout`, `/session` |
| Authelia session | `authelia_session`, domain `aurora.local`, inactivity 15m / expiry 8h / remember-me 1M |
| Service gating | Caddy `forward_auth` → Authelia, `*.aurora.local` default `two_factor` |
| Anti-lockout | Apex `aurora.local` and `auth.aurora.local` are `policy: bypass`; apex Caddy vhost does **not** `import authelia` |
| Passkeys | **Stub.** `@simplewebauthn/browser` is a dependency with *zero* imports in `src/`. The button fires a toast. |
| Authelia OIDC | **Not enabled.** No `identity_providers:` block in `configuration.yml`. |

### 1.1 The single most important finding

`AutheliaService` line 220:

```java
entry.put("password", u.passwordHash());
```

The **same bcrypt hash** is projected into Authelia. There is one credential and one
store of record already.

This reframes the whole task. We are **not** unifying credentials — that is done.
We are unifying **sessions, enrollment, and presentation**. That is a much smaller,
much safer job than it looked.

### 1.2 What the user actually experiences (the real bug list)

**0. Gated services cannot be logged into at all.** *(verified live, 2026-08-27)*
   Every `*.aurora.local` vhost is `policy: two_factor` (`configuration.yml:116`), and
   `mail-admin` is `two_factor` + `group:admins` (`:107`). But on this box:

   ```
   webauthn_credentials  0 rows
   totp_configurations   0 rows
   notification.txt      0 bytes, root-owned
   ```

   Forward-auth itself works — `mail.aurora.local` → `302 auth.aurora.local/?rd=…`.
   The user then lands on a portal demanding a second factor they have never enrolled,
   where the enrollment link is delivered by a `filesystem` notifier writing to a file
   **inside the Authelia container** that nobody will ever read.

   This is not "double login". It is **no login**. Roundcube, Stalwart admin and every
   other gated service are unreachable today. This outranks everything below.

1. **Double login.** Sign into Aurora at the apex. Click through to Roundcube.
   Authelia does not know you. Type the *same password* again. *(Only reachable as a
   problem once #0 is fixed.)*
2. **Passkeys are a lie.** The button exists and does nothing. Authelia has real
   WebAuthn support sitting unused behind it.
3. **Two front doors, two identities.** Different branding, copy, error messages.
   Nothing says they are the same system.
4. **Two logouts.** `POST /api/auth/logout` kills the Aurora session. The Authelia
   session survives. "Sign out" does not sign you out.

---

## 2. The invariant this plan must not break

From `MEMORY.md`, and enforced in `configuration.yml` + `caddy.snippet`:

> The Aurora admin plane keeps its own login and is deliberately **NOT** behind
> Authelia forward-auth, so you can never be locked out of the control plane.

Any design where "Authelia is down" ⇒ "cannot administer the box" is **rejected on
arrival**. This is the constraint that kills the obvious solution, and it is why
this document exists rather than a one-line "put the apex behind forward-auth".

The box is a homelab appliance in someone's cupboard. The control plane is how you
fix Authelia when Authelia is what broke.

---

## 3. Options considered

### Option A — Put the apex behind Authelia forward-auth
One session, trivial to build. **Rejected:** directly violates §2. An Authelia
misconfiguration, a bad `users_database.yml` projection, or a crash-looping
container locks the operator out of the only tool that can repair it.

### Option B — Aurora becomes the OIDC provider, Authelia delegates to it
Aurora is already the store of record, so this is philosophically tidy. **Rejected
for now:** writing a spec-correct OIDC provider (discovery, JWKS, PKCE, token
lifetimes, refresh) is a large security-critical surface. Authelia already ships a
certified one. Building a second, worse one is not a good trade for a homelab box.

### Option C — Session bridging: Aurora mints an Authelia session on login
Aurora logs you in, then hands you a valid `authelia_session` cookie. **Rejected:**
requires forging Authelia's session format or driving its internal API. Brittle
across Authelia upgrades, and a bug means either a broken session or a forged one.
Coupling our auth correctness to Authelia's private session encoding is a trap.

### Option D — Authelia as identity provider, Aurora as consumer, with break-glass ✅
Authelia becomes the primary identity. Aurora accepts an Authelia-established identity
**in addition to** its own local password login, retained permanently as break-glass.

- One credential (already true), one enrollment, one session, one sign-out.
- Passkeys work for real, enrolled once, in the component that already implements
  WebAuthn correctly.
- Anti-lockout preserved: local login never goes away and never depends on Authelia.

**Recommended** — but see §4.1: the *mechanism* is header-based delegation, not OIDC.

### 3.1 The honest cost of Option D

Break-glass is not free. It means Aurora permanently supports two auth paths, and
the local one must be tested to the same standard as the primary. A break-glass
door that has rusted shut is worse than no break-glass door, because you only find
out during the incident. §5 M0 exists specifically to make this testable, and the
E2E in M2 exercises it with Authelia *stopped*, not merely bypassed.

---

## 4. Target design

### 4.1 Mechanism: header-based delegation, **not** OIDC

The first draft specified OIDC. Review demonstrated two hard blockers, verified live:

```
docker exec aurora getent hosts auth.aurora.local      -> (no resolution)
docker exec aurora wget https://auth.aurora.local/     -> bad address
keytool -list -cacerts | grep -ic caddy                -> 0
docker exec aurora wget http://authelia:9091/api/health -> {"status":"OK"}
```

1. **No DNS.** The container resolves via Docker's `127.0.0.11`, which knows
   `authelia` and `caddy` but not `auth.aurora.local`. mDNS `.local` resolution is a
   *host* capability the container does not have.
2. **No CA trust.** Zero Caddy CA entries in the Java truststore, so a JWKS fetch
   fails TLS validation even with DNS.
3. **Split-horizon issuer.** OIDC requires `iss` to match exactly across browser and
   server. The browser can only reach `https://auth.aurora.local`; Aurora can only
   reach `http://authelia:9091`. Solvable with an issuer override plus a custom
   truststore -- but that is real engineering, not a config line.

Aurora and the portal are **same-site** (`aurora.local` / `auth.aurora.local`) and
Caddy is already the only ingress, so OIDC is not required for one coherent
experience:

- "Sign in with Aurora SSO" sends the browser to `https://auth.aurora.local/?rd=<back>`.
- Aurora establishes its own session from a **same-origin** forward-auth verify check
  through Caddy, reading `Remote-User` / `Remote-Groups`.
- Header stripping is already implemented and commented at `caddy.snippet:36-49`.

One credential, one enrollment, one passkey, one visible sign-in -- with no OIDC
client, no JWKS, no issuer matching, no DNS problem, no truststore work, and no
redirect-URI/domain-change coupling.

**Honest trade-off:** OIDC is more standard and portable, with cleaner claims and
token lifetimes. Header-based is Caddy-coupled and needs care that the verify route
cannot be spoofed. For a single-box appliance where Caddy is the only ingress it is
dramatically less machinery for ~90% of the benefit. Ship this; reach for OIDC only
if a concrete need appears.

**Critical:** the apex gets a *dedicated verify route*, **not** `import authelia` on
the whole vhost -- that would break section 2.

```
  Primary path:  browser -> Authelia (password + passkey + 2FA) -> Aurora verify -> session
  Break-glass:   browser -> Aurora /login?local=1 -> SQLite bcrypt -> Aurora session
  Services:      browser -> Caddy forward_auth -> Authelia -> service
```

Rules:

1. Aurora **never** sits behind blanket forward-auth. Apex stays `policy: bypass`;
   only a dedicated verify endpoint consults Authelia.
2. Local password login is a permanent, first-class, tested path -- not a debug flag.
3. Authelia is the **only** place passkeys are enrolled and verified.
4. Aurora SQLite remains the store of record. Projection never reverses. Unknown
   users are rejected, never auto-provisioned.
5. Sign-out ends both sessions.

## 5. Milestones

Each milestone is independently shippable and leaves the box working.

### M0 -- Make break-glass real *(prerequisite, do first)*
The plan leans on local login as the safety net, so harden it **before** anything
depends on it.
- Explicit `/login?local=1` route that always renders the password form.
- Never auto-redirects to SSO, even once delegation is live.
- Rate-limited (Authelia's regulation does not cover this path).
- E2E: stop the Authelia container, assert an admin can still log in and reach Settings.
- Document it on the Done page and in `README.md`.

**The rot problem, and why the E2E is only half the fix.** The risk is not the login
*form* -- it is the *credential behind it*. Once SSO is primary the operator stops
typing their local password, and passwords you never type are passwords you forget.
An E2E proves the form works; it proves nothing about whether Bruce still knows the
password in six months. So M0 also covers:
- `scripts/reset-admin-password.sh` (already exists) promoted to a documented, tested,
  first-class recovery path, surfaced on the Done page.
- Aurora re-prompts for the local password on destructive actions (Uninstall, user
  deletion) even when SSO is active -- keeping the credential warm through *use*
  rather than through testing.

**Risk if skipped:** we build the dependency before the fallback. Non-negotiable ordering.

### M0.5 -- Make the second factor enrollable *(the live outage)*
See section 1.2 bug #0. Every gated service is currently unreachable: policy is
`two_factor`, zero factors are enrolled, and the enrollment notifier writes to a
0-byte root-owned file inside the Authelia container.

Pick one (in preference order):
1. **Surface `notification.txt` in the Aurora UI.** Honest, offline-friendly, no mail
   dependency, and Aurora already bind-mounts the repo. Best fit for a LAN appliance.
2. Point the notifier at Stalwart SMTP -- it is in core and already running. Better
   long-term, but couples first-run auth to mail being configured.
3. Drop the default to `one_factor` until enrollment exists. Fastest, weakest.

Recommend **1**, with **2** as a follow-up once Stalwart's wizard is done.
E2E: enroll a factor end to end and reach `mail.aurora.local`.

### M1 -- Honesty + shared identity *(mostly cheap)*
- **Remove the fake passkey button.** Replace with real state ("Passkeys are set up in
  Aurora SSO" + deep link) or hide until M3. Shipping a button that lies is worse than
  shipping nothing.
- Brand the Authelia portal to match Aurora (custom assets): logo, colours, copy.
- Unify error copy across both forms via the existing `lib/http-error-copy.ts`.

**Correction to the first draft:** "Continue as `<user>`" (Aurora detecting an existing
Authelia session) is *not* zero-protocol-work. `authelia_session` is scoped to
`domain: aurora.local` (`configuration.yml:132`) so the browser does send it, but
Aurora cannot validate it without calling Authelia -- which lands straight back in the
DNS/TLS problem of section 4.1. It needs the same-origin verify route, so it belongs
in **M2'**, not here. The other three items remain genuinely cheap.

### M2' -- Delegated sign-in via same-origin forward-auth verify
Mechanism per section 4.1. **No OIDC.**
- Add a *dedicated* Caddy route on the apex that runs `forward_auth` against Authelia
  and proxies to a new Aurora endpoint. The rest of the apex vhost stays ungated --
  `import authelia` on the whole vhost would break the anti-lockout invariant.
- Backend `POST /api/auth/sso/verify`: trusts `Remote-User` / `Remote-Groups` **only**
  from that route, maps groups to the existing `Role`, establishes an Aurora session.
- **No new user rows.** An SSO identity for an unknown username is rejected, not
  auto-provisioned, so SQLite stays the store of record.
- Aurora login becomes: **"Sign in with Aurora SSO"** (primary) + "Use local
  password" (secondary, always present).
- Reuse `safeRedirect()` so `from=` survives the round trip without becoming an open
  redirect.

**Spoofing guard is the whole security story here.** `Remote-*` headers are only
trustworthy because Caddy strips client-supplied copies (`caddy.snippet:36-49`). The
verify endpoint must additionally refuse requests that did not arrive via that route.
Add a test asserting a direct request to `:8090` carrying forged `Remote-User` is
rejected.

### M3 — Passkeys, for real
- Enroll in Authelia (native WebAuthn). Aurora surfaces enrollment status and deep
  links into it from Settings → Account.
- Because M2 makes Authelia the primary path, a passkey now logs you into Aurora
  *and* every service. One enrollment, whole box.
- Decide and document: is passkey sufficient alone, or passkey-as-second-factor?
  Recommend passkey-as-primary with the existing `two_factor` policy on services.
- `@simplewebauthn/browser` gets removed from `package.json` if Aurora never speaks
  WebAuthn directly. Do not leave an unused crypto dependency lying around.

### M4 — Single sign-out + session coherence
- `POST /api/auth/logout` ends the Aurora session **and** redirects through
  Authelia's logout endpoint.
- Align idle behaviour: Authelia is 15m inactivity / 8h expiry. Aurora's session
  should not silently outlive it — a dead Authelia session with a live Aurora one
  is exactly the confusion M2 set out to remove.
- The 401 interceptor (just fixed) should send the user to whichever door they came
  through, preserving `from=`.

---

## 6. What could go wrong

| Risk | Mitigation |
|---|---|
| Authelia down ⇒ locked out | M0 first. Local login never depends on Authelia. E2E with the container **stopped**. |
| OIDC redirect loop between apex and `auth.` | Both are `policy: bypass`; add an E2E that follows the full redirect chain. |
| Group claim drift vs SQLite role | Projector stays one-way. Reject unknown users; never auto-provision. |
| Self-signed cert breaks OIDC discovery | Aurora must trust the core CA when calling Authelia's JWKS. Explicit truststore step. |
| `mail-admin` requires `group:admins` + 2FA | Verify Stalwart admin still reachable post-M2; it is the most policy-sensitive vhost. |
| Losing the anti-lockout property by accident | Add a test asserting the apex vhost never gains `import authelia`, mirroring the existing invariants test. |

---

| Argon2id migration breaks SSO | `AuthService.java:14-19` queues Argon2id for v0.2. Authelia's `file_password` is pinned to bcrypt cost 12 (`configuration.yml:67-69`) **deliberately**, so hashes verify on both sides without a rehash dance. Moving Aurora to Argon2id without moving `configuration.yml` in the same commit locks every user out of every service. Add a test asserting the two algorithm/cost values agree. |
| 401 interceptor yanks the browser mid-SSO round trip | `client.ts:78-99` redirects on *any* XHR 401. An in-flight poll (updates store, service-status stream) 401ing during the SSO handshake discards the callback and overwrites `from=`. Exempt auth-flow requests. **Concrete regression against just-merged work.** |
| `safeRedirect` rejects the callback path | It rejects any path whose path-part is exactly `/login`. Route the callback via `/login/callback` (fine) and never normalize to `/login`. Keep the whole round trip relative. Add an explicit test. |
| Domain change after SSO is enabled | `OnboardingService.setDomain()` (~line 891) is callable post-install and rewrites `.state.yml` + `core/.env`, but nothing re-renders auth config keyed to the domain. Make domain change a transaction that re-renders it, or refuse it once SSO is on. Less severe under M2' than under OIDC (no registered `redirect_uris`) but the portal URL still moves. |
| Admin exists before the domain is known | `OnboardingService:885-887` creates the admin then advances to `domain`. Any auth config keyed to the domain must be generated at/after the domain step, and regenerated on change. |


## 7. Recommended sequencing

**M0 -> M0.5 -> M1 -> M2' -> M3 -> M4.**

Revised after review. The first draft said "if only two milestones ship, M0 and M1".
That was wrong: M0 and M1 do not fix the worst problem on this box. Gated services
cannot be logged into at all (section 1.2 bug #0), and no amount of branding or button
honesty changes that.

- **M0** first, always. Everything else assumes the fallback works.
- **M0.5** next, because it is a live outage and cheap to fix.
- **M1** is polish that can land any time after.
- **M2'** is the real work, and far smaller than the OIDC design it replaces.

If only two ever ship: **M0 and M0.5**. They make the box recoverable and make its
services reachable.

## 8. Immediate-session-on-creation (the Cognito pattern)

Bruce raised this from experience with AWS Cognito: on sign-up, Cognito can return
authenticated tokens directly, so the app redirects the new user straight to their
account page instead of bouncing them to a login form they just proved they can pass.

### 8.1 Already done, at first run

`OnboardingController.complete()` (line ~152) already does exactly this:

```java
onboarding.markComplete();
onboarding.primaryAdmin().ifPresent(admin -> sessions.establish(admin, request));
```

And it is placed better than the naive version. The session is granted at
`POST /complete`, **not** at `POST /admin`, because at admin-creation time the wizard
still has domain, DNS, review and launch ahead of it — any of which can fail or be
abandoned. Granting a session there would hand out a login for a box that never
finished setting itself up. `guardMidOnboarding()` returns 409 once complete, so it
cannot be replayed into a fresh session.

The security argument is sound: the admin exists only because someone just chose its
username and password via `POST /admin`, so granting a session at `/complete` hands
out nothing an attacker could not get by logging in with those same credentials. It
only skips a redundant step for the operator who just set them.

**No change needed here.** Keep it.

### 8.2 Missing, for admin-created users

`UsersController.create()` has **no** equivalent. An admin creates a user and that
user is then on their own to find the login page. There is no handoff, no invite, no
first-login flow. The Cognito pattern is genuinely absent here, and this is where it
should be applied.

Note the asymmetry with §8.1: a self-chosen password justifies an immediate session,
but an admin-chosen one does not — the admin knows the credential, so a session
minted for the *admin's* browser is the wrong subject entirely.

Proposed (new **M5**):
- `POST /api/users` optionally returns a single-use, short-TTL (24h) invite token.
- Admin gets a copy-able link: `https://aurora.local/welcome?invite=<token>`.
- That page lets the new user set their **own** password (and, post-M3, enroll a
  passkey), then establishes their session and lands them on the dashboard.
- Token is single-use, expiring, revocable, and never reusable after redemption —
  the same replay reasoning as `guardMidOnboarding()`.
- Admin-set passwords stop being a thing that gets read aloud across a room.

### 8.3 Where the pattern does *not* transfer — and the fix

Cognito can return tokens on sign-up because **Cognito is the IdP performing the
registration**. In Aurora, registration happens in Aurora, but the IdP for services
is Authelia. Aurora cannot mint an `authelia_session` without forging Authelia's
session encoding — that is Option C (§3), rejected as brittle and
coupling-our-correctness-to-their-private-format.

So today, even after `/complete`, the operator has an **Aurora session and no
Authelia session**. The very first time they click through to a service they are
asked for the same password again. The double-login problem starts on first run.

**The legitimate adaptation is to invert the order.** Rather than Aurora creating a
session and trying to propagate it sideways into Authelia, let Authelia perform the
one authentication the operator does during onboarding, and have Aurora receive its
session from that via OIDC:

1. Wizard creates the admin in SQLite (unchanged).
2. Projector writes `users_database.yml` (unchanged — already synchronous on user change).
3. At `/complete`, instead of only `sessions.establish(...)`, Aurora runs the OIDC
   authorization round-trip against Authelia.
4. The operator authenticates **once**, at Authelia — the same password they just
   chose — and lands back on the dashboard holding *both* sessions.

That is one authentication for the whole box, achieved without forging anything.
It is the same user-visible outcome Bruce described, reached by the one route that
does not require Aurora to impersonate its own IdP.

**Caveat to settle during M2:** this makes first-run depend on Authelia being up. If
Authelia is unhealthy at `/complete`, the wizard **must** fall back to the current
local-session behaviour rather than trapping the operator in a half-finished install.
That fallback is the §5 M0 break-glass path doing exactly the job it was built for,
and it needs an explicit E2E: complete onboarding with Authelia stopped.

---

## 9. Open questions for Bruce

1. **Passkey model** — passkey as primary (replaces password) or as second factor?
   Recommend primary, with password retained as break-glass.
2. **Do services need to stay 2FA** once a passkey is the primary factor? Passkey +
   2FA on every service subdomain may be more friction than a homelab wants.
3. **Guest role + SSO** — should `GUEST` reach the Aurora UI at all, or only
   services?
4. **Remember-me** — Authelia allows 1 month. Should Aurora's OIDC session inherit
   that, or stay shorter for the admin plane?
5. **Invite links (§8.2)** — worth building, or is admin-sets-password acceptable for
   a homelab where the admin is usually standing next to the person?
