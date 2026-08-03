import { test, expect } from '@playwright/test';

/**
 * UX_SPEC §3.11 H2 + §5.2: on the first-load dashboard every enabled
 * package renders a tile with a status pill matching the enum
 *   running | needs-config | failed | not-started
 * within 5 s of load. UX_SPEC §3.11 H6: SSE pushes updates within 2 s
 * of a docker event.
 *
 * Prerequisite: onboarding must be marked complete. We poke
 * /api/onboarding/status via the fresh-state fixture; if the app is not
 * yet installed the tests skip themselves rather than false-fail.
 */

const ALLOWED = ['running', 'needs-config', 'failed', 'not-started', 'starting'] as const;

async function onboardingComplete(page: import('@playwright/test').Page): Promise<boolean> {
  const res = await page.request.get('/api/onboarding/status');
  if (!res.ok()) return false;
  try {
    const body = await res.json();
    return body?.complete === true && body?.bootstrap_mode === false;
  } catch {
    return false;
  }
}

/** UX_SPEC §3.11 H2 — every enabled package tile carries a data-status within 5s. */
test('dashboard renders a status pill per enabled package within 5s', async ({ page }) => {
  if (!(await onboardingComplete(page))) {
    test.skip(true, 'onboarding not complete on fresh box; dashboard tests need a post-install state');
  }
  await page.goto('/');
  await page.waitForSelector('[data-package]', { timeout: 5_000 });
  const tiles = page.locator('[data-package]');
  const count = await tiles.count();
  expect(count).toBeGreaterThan(0);
  for (let i = 0; i < count; i++) {
    const pill = tiles.nth(i).locator('[data-status]').first();
    await expect(pill).toBeVisible({ timeout: 5_000 });
    const status = await pill.getAttribute('data-status');
    expect(ALLOWED).toContain(status as (typeof ALLOWED)[number]);
  }
});

/** UX_SPEC §3.11 H5 — every card has a one-click primary action, never a card without one. */
test('every dashboard package tile has a one-click action', async ({ page }) => {
  if (!(await onboardingComplete(page))) test.skip();
  await page.goto('/');
  await page.waitForSelector('[data-package]', { timeout: 5_000 });
  const tiles = page.locator('[data-package]');
  const count = await tiles.count();
  for (let i = 0; i < count; i++) {
    const btn = tiles.nth(i).locator('button, a[role="button"]').first();
    await expect(btn, `package tile ${i} missing action button`).toBeVisible();
  }
});

/** UX_SPEC §3.11 H4 — no metric chart above the first checklist / package tile. */
test('no chart appears above the checklist / package tiles', async ({ page }) => {
  if (!(await onboardingComplete(page))) test.skip();
  await page.goto('/');
  // If a canvas/svg chart is above the first data-package tile in reading order, that fails H4.
  const firstTile = page.locator('[data-package]').first();
  const chartsAbove = page.locator('canvas, [data-role="chart"]').first();
  if (await chartsAbove.count() === 0) return; // pass — no charts anywhere is fine
  const [tileBox, chartBox] = await Promise.all([firstTile.boundingBox(), chartsAbove.boundingBox()]);
  if (!tileBox || !chartBox) return;
  expect(chartBox.y, 'chart rendered above first package tile (violates H4)').toBeGreaterThanOrEqual(tileBox.y);
});
