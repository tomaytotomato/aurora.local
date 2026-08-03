# Aurora E2E — baseline (step-3 of UX self-improvement chain)

- **When:** 1 worker, baseline snapshot
- **Total tests:** 60
- **Passed (expected):** 34
- **Failed (unexpected):** 16
- **Flaky:** 0
- **Skipped:** 10
- **Wall time:** 309.8s

Failures are **expected** at baseline — they describe the desired UX and cite the
UX_SPEC criterion each defends. Worker step-5 (iter-1 implement) will close them.

## Failures

### `done-page-checklist.spec.ts › renders a card per enabled package with a data-status pill`
- **why it failed:** Error: expect(received).toBeGreaterThan(expected)
- **UX_SPEC criterion:** (not tagged in test — see file)

### `done-page-checklist.spec.ts › every package card has an approved primary action label`
- **why it failed:** Error: expect(received).toBeGreaterThan(expected)
- **UX_SPEC criterion:** (not tagged in test — see file)

### `done-page-checklist.spec.ts › single "Go to my dashboard" primary CTA at the bottom`
- **why it failed:** Error: expect(locator).toBeVisible() failed
- **UX_SPEC criterion:** UX_SPEC §5.1: failed(0) < needs-config(1) < not-started(2) < starting(3) < running(4)

### `no-cli-instructions.spec.ts › /onboarding/tls: no <pre> or shell-text <code>`
- **why it failed:** Error: expected no <code> with shell text (UX_SPEC G3), found:
- **UX_SPEC criterion:** (not tagged in test — see file)

### `no-cli-instructions.spec.ts › /onboarding/tls: no shell-command patterns in visible copy`
- **why it failed:** Error: shell-text hits on /onboarding/tls: ["sudo","sudo",null]
- **UX_SPEC criterion:** (not tagged in test — see file)

### `wizard-happy-path.spec.ts › welcome → continue navigates to /onboarding/admin`
- **why it failed:** Error: expect(locator).toBeEnabled() failed
- **UX_SPEC criterion:** UX_SPEC §3.2 W4 + W5: continue is unconditionally enabled and lands on /admin

### `wizard-happy-path.spec.ts › admin step prefills username and password`
- **why it failed:** Error: expect(locator).toHaveValue(expected) failed
- **UX_SPEC criterion:** UX_SPEC §3.3 A1 + A2: username prefilled "aurora", password ≥ 20 chars

### `wizard-happy-path.spec.ts › admin continue is disabled until saved-password checkbox is ticked`
- **why it failed:** Error: expect(locator).toBeDisabled() failed
- **UX_SPEC criterion:** UX_SPEC §3.3 A4: continue disabled until the "saved this password" checkbox is ticked

### `wizard-happy-path.spec.ts › domain step prefilled with aurora.local and advances to /packages`
- **why it failed:** Error: expect(locator).toHaveValue(expected) failed
- **UX_SPEC criterion:** UX_SPEC §3.4 D1 + D5: domain prefilled aurora.local, continue advances

### `wizard-happy-path.spec.ts › packages step: core is locked-on`
- **why it failed:** Error: expect(locator).toBeChecked() failed
- **UX_SPEC criterion:** UX_SPEC §3.5 P2: core is always selected + disabled

### `wizard-happy-path.spec.ts › packages continue label mirrors selection count`
- **why it failed:** Test timeout of 30000ms exceeded.
- **UX_SPEC criterion:** UX_SPEC §3.5 P5: continue label matches /^Continue with \d+ packages?$/

### `wizard-happy-path.spec.ts › secrets step names the number of generated secrets`
- **why it failed:** Error: expect(received).toMatch(expected)
- **UX_SPEC criterion:** UX_SPEC §3.6 S1: secrets screen names the count from GET /plan

### `wizard-happy-path.spec.ts › secrets step contains no milestone/roadmap language`
- **why it failed:** Error: expect(received).not.toMatch(expected)
- **UX_SPEC criterion:** UX_SPEC §3.6 S2 + §6 anti-pattern 9: no milestone/version leakage

### `wizard-happy-path.spec.ts › tls step exposes a Download root CA control`
- **why it failed:** Error: expect(locator).toBeVisible() failed
- **UX_SPEC criterion:** UX_SPEC §3.8 T1: root-CA download button exposes caddy-root.crt

### `wizard-happy-path.spec.ts › tls step exposes a "Skip for now" secondary action`
- **why it failed:** Error: expect(locator).toBeVisible() failed
- **UX_SPEC criterion:** UX_SPEC §3.8 T5: skip-for-now action is present

### `wizard-happy-path.spec.ts › review install button label is exactly "Install"`
- **why it failed:** Test timeout of 30000ms exceeded.
- **UX_SPEC criterion:** UX_SPEC §3.9 R3: install button label is the single word "Install"

## Skipped

- adguard-password-check.spec.ts › done page: privacy card shows "Finish AdGuard setup" when password unset
- adguard-password-check.spec.ts › done page: privacy card status pill is needs-config while password unset
- adguard-password-check.spec.ts › dashboard checklist lists AdGuard as a needs-config blocker
- done-page-checklist.spec.ts › failed/not-started packages surface a top banner
- error-recovery.spec.ts › install failure shows retry + plain-English reason (no stack trace)
- error-recovery.spec.ts › install log emits progress within 3s of clicking Install
- error-recovery.spec.ts › done page: any failed package exposes a Retry action
- package-status-probing.spec.ts › dashboard renders a status pill per enabled package within 5s
- package-status-probing.spec.ts › every dashboard package tile has a one-click action
- package-status-probing.spec.ts › no chart appears above the checklist / package tiles
