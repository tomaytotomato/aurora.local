# Onboarding launch/complete ordering (409 on /onboarding/launch)

## The bug

`OnboardingReview.vue` called `install()` then `complete()` (flips
`onboarding.complete = true`) and only then routed to `/onboarding/done`,
which calls `POST /onboarding/launch`. `OnboardingService.guardMidOnboarding()`
throws when `isComplete()` is true, so the Done page's launch call always
409'd. Nobody could finish the wizard.

## Chosen fix

Move the commit (`POST /onboarding/complete`) to *after* a successful
launch, not before it. Concretely:

- `OnboardingReview.vue` no longer calls `complete()` at all — it only
  installs and navigates to `/onboarding/done`.
- `OnboardingDone.vue` calls `complete()` once there is nothing left that
  could still fail: either the launch stream reports success, or there
  were no packages to start in the first place. Added a
  `finishOnboarding()` helper used from three call sites: `onLaunchSuccess`,
  the mount-time rehydrate branch when a stored job snapshot is already
  `success`, and the mount branch when `toStart.length === 0`.
- Backend `guardMidOnboarding()` is untouched — `/install`, `/launch` and
  `/complete` are all still blocked once onboarding is genuinely complete.
  Because we no longer call `/complete` before `/launch`, the guard now
  passes at the point the frontend needs it to, and still fires if a
  finished onboarding is replayed.

## Why not the alternatives

- Relaxing the backend guard on `/launch` specifically would let an
  unauthenticated caller re-run `up.sh` against a box that has already
  finished onboarding (an admin exists) — the exact class of thing the
  guard exists to stop. Rejected.
- Having `OnboardingDone.vue` call authenticated endpoints instead of the
  public onboarding ones doesn't work either: at the point Done needs to
  call `/launch`, no session exists yet (that's the whole reason these
  routes are public in bootstrap mode) — completing is what unlocks the
  authenticated surface, not the other way round.

## A second bug found along the way

`useOnboardingStore().draft` (server-truth snapshot used by the router
guard) is only populated by `hydrate()`, which the router only calls once
per SPA lifetime. Calling `OnboardingApi.complete()` directly (as the old
code did) never updated the local store, so `draft.complete` stayed stale
`false` for the rest of the session — meaning clicking "Go to my
dashboard" would have bounced the user straight back into the wizard even
if the 409 hadn't existed. Added `store.markOnboardingComplete()`, called
after every successful (or already-complete) `/complete` call, and gated
`canGoToDashboard` on an explicit `onboardingCommitted` ref rather than
inferring it from `launchState`, closing a race where the button could be
clicked before the commit call resolved.

## Progress

- [x] Root-caused the ordering contradiction and the stale-store bug.
- [x] Removed `complete()` from `OnboardingReview.vue`.
- [x] Added `finishOnboarding()` + `onboardingCommitted` gating to
      `OnboardingDone.vue`.
- [x] Added `markOnboardingComplete()` to the onboarding store.
- [x] Backend integration test walking install → launch → complete in
      frontend order, plus guard-still-fires and retry-after-failure
      coverage.
- [x] Backend suite green.
- [x] Frontend typecheck + unit tests green.
