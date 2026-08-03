import { test, expect } from '@playwright/test';

/**
 * UX_SPEC §3.10 X6 + §5.1 (1) — if AdGuard/privacy is enabled and its
 * admin password is unset, the Done page marks it as a red blocker.
 *
 * Sarah's mental model: "AdGuard is set up." Aurora's job: not to lie.
 * If the AdGuard admin password is still the default, Aurora surfaces
 * that as a needs-config blocker at the top of the Done page and on the
 * dashboard checklist.
 */

test.describe('adguard first-run blocker', () => {
  /** UX_SPEC §3.10 X6 — privacy tile shows "Finish AdGuard setup" while password is unset. */
  test('done page: privacy card shows "Finish AdGuard setup" when password unset', async ({ page }) => {
    await page.goto('/onboarding/done');
    const privacy = page.locator('[data-package="privacy"]');
    if ((await privacy.count()) === 0) {
      test.skip(true, 'privacy package not enabled in this run');
    }
    const label = await privacy.locator('button, a[role="button"]').first().innerText();
    expect(label.trim()).toMatch(/Finish (AdGuard )?setup/i);
  });

  /** UX_SPEC §3.10 X6 — status pill is needs-config (or failed) when AdGuard is unconfigured. */
  test('done page: privacy card status pill is needs-config while password unset', async ({ page }) => {
    await page.goto('/onboarding/done');
    const privacy = page.locator('[data-package="privacy"]');
    if ((await privacy.count()) === 0) test.skip();
    const status = await privacy.locator('[data-status]').first().getAttribute('data-status');
    expect(['needs-config', 'failed']).toContain(status);
  });

  /** UX_SPEC §5.1 (3) — dashboard checklist elevates AdGuard to a blocker row. */
  test('dashboard checklist lists AdGuard as a needs-config blocker', async ({ page }) => {
    await page.goto('/');
    const check = page.locator('[data-checklist="get-started"]');
    if ((await check.count()) === 0) test.skip(true, 'no get-started checklist rendered');
    const adguardRow = check.locator('[data-row="privacy"], [data-row="adguard"]').first();
    await expect(adguardRow).toBeVisible();
    const tone = await adguardRow.getAttribute('data-tone');
    expect(['warn', 'err']).toContain(tone);
  });
});
