import { test, expect } from '@playwright/test';

/**
 * Login-polish (2026-08-02) — two-part contract:
 *
 * 1. LoginView mounts <AuroraBackground scrim="strong"> directly, matching
 *    /dashboard/home and /onboarding/welcome. The `.aurora-bg` element +
 *    `img.photo` must both be present on the pre-auth /login route.
 *
 * 2. The "Start onboarding" CTA is gated on the onboarding status:
 *      - complete=true  → CTA absent
 *      - complete=false → CTA present
 *    Both stubbed via page.route so the assertion is deterministic
 *    regardless of the live box's onboarding state.
 */

test('login page renders the aurora photo background', async ({ page }) => {
  await page.goto('/login');
  const layer = page.locator('.aurora-bg');
  await expect(layer).toHaveCount(1);
  await expect(layer.locator('img.photo')).toHaveCount(1);
});

test('login page hides onboarding CTA when onboarding is complete', async ({ page }) => {
  await page.route('**/api/onboarding/status', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        complete: true,
        bootstrap_mode: false,
        step: 'done',
      }),
    });
  });
  // Stubbing the full onboarding hydration payload isn't necessary — the
  // store falls back to `status` from draft, which is derived from
  // complete/bootstrap_mode. Any 404 on the /onboarding fetch leaves
  // showOnboardingCta=false, which is also acceptable for this assertion.
  await page.route('**/api/onboarding', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        complete: true,
        bootstrap_mode: false,
        step: 'done',
        admin: { username: '', password_saved_acknowledged: true },
        packages: { enabled: [] },
        dns_mode: null,
      }),
    });
  });

  await page.goto('/login');
  await page.waitForLoadState('networkidle');
  await expect(page.locator('[data-test="onboarding-cta"]')).toHaveCount(0);
});

test('login page shows onboarding CTA when onboarding is not complete', async ({ page }) => {
  await page.route('**/api/onboarding/status', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        complete: false,
        bootstrap_mode: false,
        step: 'welcome',
      }),
    });
  });
  await page.route('**/api/onboarding', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        complete: false,
        bootstrap_mode: false,
        step: 'welcome',
        admin: { username: '', password_saved_acknowledged: false },
        packages: { enabled: [] },
        dns_mode: null,
      }),
    });
  });

  await page.goto('/login');
  await page.waitForLoadState('networkidle');
  await expect(page.locator('[data-test="onboarding-cta"]')).toHaveCount(1);
});
