# Onboarding session + back-navigation guard

Two faults from the owner's first real walk through the wizard.

## Fault 1 — finishing onboarding doesn't log you in

`POST /api/onboarding/admin` created the admin row but never touched the
HTTP session. `POST /api/onboarding/complete` (the very next call the
wizard makes, and the only one that runs exactly once) didn't either. So
`OnboardingDone.vue`'s "Go to my dashboard" button pushed to `/`, the
router guard asked `GET /api/auth/me`, got `authenticated: false`, and
bounced straight to `/login` — the last thing an operator should see
after choosing a password and watching the box start.

### Where the session gets established, and why there

`POST /api/onboarding/complete`, not `POST /api/onboarding/admin`.

At admin-creation time there are still several steps that can fail or be
abandoned (domain, secrets, DNS, review, launch) — granting a session
that early would hand out a login for a box that hasn't finished setting
itself up, and would make a half-abandoned wizard look like a working
login. `/complete` is the one call in the whole flow that only ever
succeeds once: `OnboardingService.guardMidOnboarding()` refuses it with
409 the moment `isComplete()` is already true. It's also already the
frontend's designated "nothing left that can still fail" signal (see
`dev/notes/onboarding-409-progress.md` — `OnboardingDone.vue` only calls
it after a launch has actually succeeded, or there was nothing to
launch).

### What stops this being a credential-free session

Nothing new. By the time `/complete` can succeed, an admin has already
been created by someone who chose its username and password via
`POST /admin` — the endpoint is only reachable in bootstrap mode
(`users.count() == 0`), i.e. by whoever gets there first. Establishing a
session at `/complete` hands that same person nothing they couldn't
already get by then calling `POST /auth/login` with the credentials they
themselves just set. It just removes the redundant extra step. Verified
directly: `guardMidOnboarding()` is untouched, still throws
`IllegalStateException` on `isBootstrapMode()` (no admin yet) or
`isComplete()` (already done) — a replayed/second `/complete` call still
409s and mints no session (see
`OnboardingCompleteEstablishesSessionIntegrationTest.a_second_unauthenticated_complete_call_cannot_mint_a_session`).

### Implementation

- New `SessionService` (`backend/src/main/java/.../services/SessionService.java`):
  the session-rotation + `SecurityContextHolder` logic that used to live
  only in `AuthController.login`, extracted so there is exactly one place
  that establishes a session. `AuthController` now delegates to it.
- `OnboardingService.primaryAdmin()`: exposes the wizard-created admin
  (`AdminUserRepo.findFirst()`) so the controller can hand it to
  `SessionService` without needing the password again.
- `OnboardingController.complete()`: after `markComplete()` succeeds,
  calls `sessions.establish(admin, request)` for the primary admin.
- Never logs a password or session token — `SessionService` only ever
  touches the username and role.

### Test

`OnboardingCompleteEstablishesSessionIntegrationTest` walks
`/admin` → `/install` → `/launch` → `/complete` against the real HTTP
layer (no `/auth/login` call anywhere) and asserts:
- before `/complete`, `GET /api/auth/me` is anonymous;
- the session `/complete` leaves behind reaches `GET /api/auth/me`
  (`authenticated: true`, correct username/role) and `GET /api/packages`
  (200) with no login call;
- a second, session-less `/complete` call still 409s and hands out no
  session cookie.

Along the way this was the first integration test in the whole suite to
call `GET /api/auth/me` (and `GET /api/packages` with a non-empty
fixture list) through the strict `OpenApiConformance` matcher every
`AuroraIntegrationTest` runs automatically. That turned up two more
genuine, pre-existing spec/backend drifts (not introduced by this piece
of work): `Session.role` and `Package`'s `recommends` /`profiles`/
`requiredEnv`/`postInstallNotes`/`sso` fields aren't in `openapi.yaml`
for `GET /auth/me` and `GET /packages` respectively (the latter already
flagged as "will show the moment anything tests it" on the sibling
`GET /packages/{name}` entry). Per this piece of work's constraints,
`openapi.yaml` itself is untouched — both are carved out narrowly in
`OpenApiConformance.KNOWN_UNDOCUMENTED_RESPONSE_FIELDS` (test-support
code, not the spec) with a full explanation, and logged as an addendum
in `dev/notes/api-contract-testing-progress.md`. `strip()` also gained an
`ArrayNode` branch — it only handled a bare object before, since nothing
had exercised the list-shaped case.

## Fault 2 — back-navigation into a finished wizard

"After aurora is started, lock out the previous onboarding steps."

### The bug

The router guard's onboarding check only fired `if (needsOnboarding)`.
Once `needsOnboarding` was false (wizard done), the very next line was
`if (to.meta.public) return true` — and every `/onboarding/*` route
carries `meta.public: true` (so it works pre-auth *during* the wizard).
So a completed box let a browser back-button press, or a stale bookmark,
straight back into any wizard step with no further checks at all.

### The fix

Extracted the guard into a named, exported `onboardingGuard()` function
(`frontend/src/router/index.ts`) — same logic, but now independently
testable — and added one more branch, checked after the
`needsOnboarding` block and before the public-route passthrough: if
onboarding is done and the target route carries `meta.onboarding`,
redirect to `/` rather than falling through. A redirect, not a 404 or an
error page — the operator did nothing wrong by clicking back; there's
just nothing left to do there.

### Does the backend refuse too?

Yes, already — this piece of work didn't need to add that. Every
mutating onboarding endpoint (`PATCH /onboarding`, `POST /install`,
`POST /launch`, `POST /sso`, plus the deprecated `/domain` and
`/packages` routes that delegate to the same `patch()`) runs through
`OnboardingService.guardMidOnboarding()`, which 409s once
`isComplete()` is true — confirmed by the pre-existing
`OnboardingLaunchOrderingIntegrationTest` (`launch_after_complete_is_still_rejected_with_409`,
`a_completed_onboarding_also_refuses_a_second_install_and_a_second_complete`)
and re-confirmed by the new session test above. So a curl straight past
the Vue guard hits the same wall the frontend does; the router fix is
about not showing the operator a wizard screen that would fail, not
about closing a hole that was actually open.

### Test

`frontend/src/router/index.spec.ts` (new): a completed onboarding route
redirects to `/`; a genuinely incomplete one still resolves; normal
authenticated navigation is unaffected once onboarding is done; the
guard doesn't re-hydrate on every navigation; and — the regression this
depends on — `markOnboardingComplete()` (added for the 409 fix, see
`onboarding-409-progress.md`) takes effect on the very next guard call
without a fresh `hydrate()`.

## Testbed proof

Rebuilt clean with `AURORA_TESTBED_PACKAGES="core dashboard"` (genuine
first run — no admin, no `.state.yml`). Walked the API in the frontend's
own order and captured real output; see the final report for the
transcript. Summary: `/admin` → `/install` → `/launch` → `/complete`
leaves the caller's cookie able to hit `GET /api/packages` with no
`/auth/login` call, and re-hitting `/complete` (or any mutating
onboarding endpoint) after that 409s.

## Test counts

- Backend: 747 → 749 (`mvn test`, Java 25). 0 failures both before and
  after.
- Frontend: 501 → 506 (`npm run test:unit`). `npm run typecheck` clean
  both before and after.
