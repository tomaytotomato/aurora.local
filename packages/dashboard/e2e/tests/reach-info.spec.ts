import { test, expect } from '@playwright/test';

/**
 * iter-3 P1a — Reach-this-box panel (mDNS host + LAN IP with Copy).
 *
 * The productionize fix for the "aurora.local won't resolve on my Mac"
 * failure Bruce hit on 2026-08-02. The panel is rendered by the shared
 * <ReachInfo /> component on both:
 *   - /onboarding/done (variant='card', bordered)
 *   - /dashboard/home System card (variant='inline', borderless)
 *
 * These assertions self-skip on the fresh e2e box because the Done page
 * lives behind wizard-complete state, and DashboardHome behind auth.
 * BL5 (auth fixture) will unlock them.
 */

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

test('P1a onboarding/env exposes lanIp so the ReachInfo panel can render', async ({ page }) => {
  const res = await page.request.get('/api/onboarding/env');
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  // lanIp may legitimately be null on a box without a routable LAN
  // interface, but the field must at least be present in the shape.
  expect(body).toHaveProperty('lanIp');
  expect(body).toHaveProperty('hostname');
});

test('P1a DashboardHome System card renders the inline ReachInfo panel with a Copy button', async ({ page }) => {
  if (!(await onboardingComplete(page))) {
    test.skip(true, 'DashboardHome behind auth; BL5 fixture will unlock this');
  }
  await page.goto('/');
  const reach = page.locator('[data-card="system"] [data-test="reach-info"]');
  await expect(reach).toBeVisible();

  const mdns = reach.locator('[data-test="reach-mdns"]');
  await expect(mdns).toBeVisible();
  await expect(mdns).toContainText(/http:\/\/[a-z0-9.-]+:\d+/);

  const copy = reach.locator('[data-test="reach-copy-mdns"]');
  await expect(copy).toBeVisible();
  await expect(copy).toHaveText(/copy/i);
});

test('P1a OnboardingDone renders the card-variant ReachInfo panel with help text', async ({ page }) => {
  // Done page is behind wizard-complete. Skip when not applicable.
  await page.goto('/onboarding/done');
  const reach = page.locator('[data-test="reach-info"]');
  if ((await reach.count()) === 0) {
    test.skip(true, '/onboarding/done not renderable on this box (bootstrap or auth-blocked)');
  }
  await expect(reach).toBeVisible();
  await expect(reach.locator('[data-test="reach-mdns"]')).toBeVisible();
  await expect(reach.locator('[data-test="reach-help"]')).toBeVisible();
  await expect(reach.locator('[data-test="reach-help"]'))
    .toContainText(/Firefox on macOS|Unable to connect/);
});
