import { test, expect } from '@playwright/test';

/**
 * Full sequential wizard walkthrough — drives Welcome through Done via the
 * real UI, one step at a time, the way an operator actually would. No API
 * shortcuts for admin creation or package selection.
 *
 * This spec exists because neither of the other two "wizard" specs
 * actually walks this path for real:
 *
 *   - wizard-happy-path.spec.ts visits each step in isolation via
 *     page.goto(route) and asserts on that step's own markup. Its last
 *     test only reads the Review page's button label — it never clicks
 *     Install, so it never drives past Review at all.
 *   - done-launch.spec.ts seeds an admin and PATCHes enabled_packages
 *     directly via the API as a documented "state-seeding escape hatch"
 *     (see its own header comment), and deliberately never calls
 *     POST /api/onboarding/complete.
 *
 * OnboardingReview.vue's install() calls POST /api/onboarding/complete
 * before routing to /onboarding/done; OnboardingDone.vue's "Start
 * services" button then POSTs /api/onboarding/launch. OnboardingService
 * .guardMidOnboarding() 409s that launch once onboarding.complete is
 * true — which it now is, because Review just set it. Neither existing
 * spec calls both endpoints in the order the real UI does, so neither
 * can observe the 409 a real user hit. This spec drives both, in order,
 * through the actual pages.
 *
 * Written to tolerate a box that already has an admin account (the
 * OnboardingAdmin "already created" branch skips the create form) so it
 * can run against a box that a previous spec has partially walked, not
 * only a bootstrap-mode-true fresh install.
 */

test.describe('wizard sequential journey (real UI, no API shortcuts)', () => {
  test('Welcome through Review→Install reaches Done, and Start services reports what /launch actually returned', async ({ page }) => {
    test.slow();

    const continueBtn = () => page.getByRole('button', { name: /^Continue$/ }).first();

    // Welcome
    await page.goto('/onboarding/welcome');
    await continueBtn().click();

    // Admin — branch on whether an account already exists on this box.
    await page.waitForURL(/\/onboarding\/admin/, { timeout: 10_000 });
    const savedCheckbox = page.getByRole('checkbox', { name: /saved this password/i });
    if ((await savedCheckbox.count()) > 0) {
      await savedCheckbox.check();
    }
    await continueBtn().click();

    // Domain — advancing here now also seeds .state.yml's enabled[] with
    // the mandatory baseline (core, storage); see OnboardingDomain.vue.
    // The interactive package-picker step that used to sit here is gone
    // (2026-08-15) — everything beyond the mandatory set is added later
    // from the Apps catalogue, not chosen mid-wizard.
    await page.waitForURL(/\/onboarding\/domain/, { timeout: 10_000 });
    await continueBtn().click();

    // SSO — router order is welcome/admin/domain/sso/secrets/dns/tls/review/done.
    // Accept the default (opt in): this is what guarantees `identity` is
    // part of the enabled set, so there is always something beyond core/
    // storage for "Start services" below to actually bring up on a box
    // that already has core running from a previous spec.
    await page.waitForURL(/\/onboarding\/sso/, { timeout: 10_000 });
    await continueBtn().click();

    // Secrets
    await page.waitForURL(/\/onboarding\/secrets/, { timeout: 10_000 });
    await continueBtn().click();

    // DNS
    await page.waitForURL(/\/onboarding\/dns/, { timeout: 10_000 });
    await continueBtn().click();

    // TLS
    await page.waitForURL(/\/onboarding\/tls/, { timeout: 10_000 });
    await continueBtn().click();

    // Review — click Install. This is the exact call Sarah made that
    // 409'd on the done page: install() PATCHes the draft, calls
    // /install, then POSTs /api/onboarding/complete before navigating on.
    await page.waitForURL(/\/onboarding\/review/, { timeout: 10_000 });
    let completeStatus: number | null = null;
    page.on('response', (res) => {
      if (res.url().endsWith('/api/onboarding/complete') && res.request().method() === 'POST') {
        completeStatus = res.status();
      }
    });
    await page.getByRole('button', { name: /^Install$/ }).click();

    // install() waits ~350ms after /complete resolves before navigating.
    await page.waitForURL(/\/onboarding\/done/, { timeout: 20_000 });
    expect(completeStatus, 'POST /api/onboarding/complete status').toBe(200);

    // Done — click "Start services" (if Aurora thinks there's anything to
    // start) and record what /launch actually returned. This is the
    // assertion the other two specs cannot make: it observes the real
    // consequence of onboarding.complete already being true by the time
    // this request goes out.
    let launchStatus: number | null = null;
    let launchBody = '';
    page.on('response', async (res) => {
      if (res.url().endsWith('/api/onboarding/launch') && res.request().method() === 'POST') {
        launchStatus = res.status();
        launchBody = await res.text().catch(() => '');
      }
    });

    // The "Media server" preset above guarantees packages this box has
    // never brought up, so this CTA must render — if it doesn't, that's
    // its own bug (or the box's state is not what this spec assumes) and
    // the test should fail loudly rather than quietly return.
    const startBtn = page.getByTestId('start-services');
    await expect(startBtn, 'expected the "Start services" CTA to render given a fresh package selection').toBeVisible({ timeout: 10_000 });

    await startBtn.click();
    await page.waitForTimeout(2_000);
    expect(launchStatus, `POST /api/onboarding/launch status (body: ${launchBody})`).toBe(202);
  });
});
