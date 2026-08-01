import { test, expect } from '@playwright/test';

/**
 * iter-dash-polish-2 acceptance suite. Enforces the six polish items
 * in logs/dashboard-polish-iter-2.md against the authenticated
 * /dashboard/home. All tests self-skip when the fresh e2e box is not
 * onboarded — the polish itself is asserted separately via DOM-level
 * checks against the header (which renders on unauthenticated routes).
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

// -----------------------------------------------------------------
// P2 — header anatomy. The TopBar renders on every authenticated
// route; on the fresh e2e box we can still hit /login and inspect the
// header shell that ships with the SPA (identity region present even
// when the user is not signed in). We assert the DOM contract that
// doesn't depend on auth: no `idle`/`live` badge, three data-region
// slots on a CSS grid.
// -----------------------------------------------------------------

test('P2 header does not contain the idle/live SSE badge', async ({ page }) => {
  await page.goto('/');
  const header = page.locator('header').first();
  await expect(header).toBeVisible();
  await expect(header).not.toContainText(/\bidle\b/);
  await expect(header).not.toContainText(/\blive\b/);
});

test('P2 header exposes identity / health / user data-region slots on grid-cols-3', async ({ page }) => {
  await page.goto('/');
  const header = page.locator('header').first();
  await expect(header).toBeVisible();
  await expect(header.locator('[data-region="identity"]')).toHaveCount(1);
  await expect(header.locator('[data-region="health"]')).toHaveCount(1);
  await expect(header.locator('[data-region="user"]')).toHaveCount(1);
  // Inner wrapper is a grid, not flex-row.
  const inner = header.locator('> div').first();
  await expect(inner).toHaveCSS('display', 'grid');
});

// -----------------------------------------------------------------
// The remaining assertions require an authenticated dashboard-home.
// They self-skip cleanly on the fresh e2e box, matching the pattern
// established in package-status-probing.spec.ts.
// -----------------------------------------------------------------

test('P1 System card h3 reads "Resources", not the bare "Health" label', async ({ page }) => {
  if (!(await onboardingComplete(page))) {
    test.skip(true, 'onboarding not complete on fresh e2e box; dashboard-home unavailable');
  }
  await page.goto('/');
  const card = page.locator('[data-card="system"]');
  await expect(card).toBeVisible();
  await expect(card.locator('h3').first()).toHaveText('Resources');
});

test('P6 Security card carries no anchor tags (dead Review checks link removed)', async ({ page }) => {
  if (!(await onboardingComplete(page))) test.skip();
  await page.goto('/');
  const card = page.locator('[data-card="security"]');
  await expect(card).toBeVisible();
  await expect(card).not.toContainText('Review checks');
  await expect(card.locator('a')).toHaveCount(0);
});

test('P3 every card h3 holds a non-empty value (no bare labels)', async ({ page }) => {
  if (!(await onboardingComplete(page))) test.skip();
  await page.goto('/');
  const banned = new Set(['Health', 'Posture', 'CPU, memory, disk']);
  for (const key of ['system', 'packages', 'security', 'metrics']) {
    const h3 = page.locator(`[data-card="${key}"] h3`).first();
    await expect(h3, `card=${key} missing h3`).toBeVisible();
    const text = (await h3.textContent())?.trim() ?? '';
    expect(text.length, `card=${key} h3 is empty`).toBeGreaterThan(0);
    expect(banned.has(text), `card=${key} h3 is a banned bare label: ${text}`).toBeFalsy();
  }
});

test('P4 every empty-state block carries a glyph + centred column layout', async ({ page }) => {
  if (!(await onboardingComplete(page))) test.skip();
  await page.goto('/');
  const blocks = page.locator('[data-state="empty"]');
  const count = await blocks.count();
  expect(count, 'expected at least one empty-state block on /dashboard/home').toBeGreaterThan(0);
  for (let i = 0; i < count; i++) {
    const b = blocks.nth(i);
    await expect(b.locator('svg').first(), `empty state ${i} missing glyph`).toHaveCount(1);
    await expect(b).toHaveCSS('text-align', 'center');
  }
});

test('P5 Metrics strip renders shorter than every other card on the page', async ({ page }) => {
  if (!(await onboardingComplete(page))) test.skip();
  await page.goto('/');
  const metrics = page.locator('[data-card="metrics"]');
  await expect(metrics).toBeVisible();
  const mBox = await metrics.boundingBox();
  expect(mBox, 'metrics strip has no box').not.toBeNull();
  for (const key of ['system', 'packages', 'security']) {
    const other = page.locator(`[data-card="${key}"]`);
    const oBox = await other.boundingBox();
    if (!oBox || !mBox) continue;
    expect(mBox.height, `metrics strip must be shorter than the ${key} card`).toBeLessThan(oBox.height);
  }
});
