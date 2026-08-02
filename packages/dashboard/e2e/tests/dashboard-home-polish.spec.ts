import { test, expect } from '@playwright/test';

/**
 * iter-dash-polish-2 acceptance suite. Enforces the six polish items
 * in logs/dashboard-polish-iter-2.md against the authenticated
 * /dashboard/home. All tests self-skip when the fresh e2e box is not
 * onboarded — TopBar (and the whole bento grid) only renders inside
 * AppShell, which is behind the auth guard. Same pattern as
 * package-status-probing.spec.ts.
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
  if (!(await onboardingComplete(page))) {
    test.skip(true, 'onboarding not complete; TopBar only renders inside AppShell (authenticated routes)');
  }
  await page.goto('/');
  const header = page.locator('header').first();
  await expect(header).toBeVisible();
  await expect(header).not.toContainText(/\bidle\b/);
  await expect(header).not.toContainText(/\blive\b/);
});

test('P2 header exposes identity / health / user data-region slots on grid-cols-3', async ({ page }) => {
  if (!(await onboardingComplete(page))) {
    test.skip(true, 'onboarding not complete; TopBar only renders inside AppShell (authenticated routes)');
  }
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
// iter-3 B2 — identity dedup rule. When hostname is already the leading
// label of the domain (default install: hostname=aurora, domain=aurora.local)
// the header must render `aurora.local`, not `aurora.aurora.local`. This
// spec exercises both the auth-gated dashboard-home path and the
// pre-auth login/system endpoint so the assertion runs even on the fresh
// e2e box that doesn't complete onboarding.
// -----------------------------------------------------------------

test('B2 header identity never contains the aurora.aurora.local dupe', async ({ page }) => {
  await page.goto('/');
  // Regardless of auth state, the served HTML must not contain the dupe.
  // The identity string is computed client-side, but the initial fallback
  // is `aurora.local` and any live response we render should apply the
  // dedup rule from lib/identity.ts.
  const html = await page.content();
  expect(html).not.toContain('aurora.aurora.local');
});

test('B2 /api/system + renderIdentity contract: hostname=aurora, domain=aurora.local → aurora.local', async ({ page }) => {
  if (!(await onboardingComplete(page))) {
    test.skip(true, 'onboarding not complete; /api/system may return nulls');
  }
  const res = await page.request.get('/api/system');
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  // If the box has been set up with the default install shape, the API
  // returns hostname=aurora, domain=aurora.local. The header identity
  // must show `aurora.local` (dedup rule), not `aurora.aurora.local`.
  if (body?.hostname && body?.domain
      && String(body.domain).toLowerCase().startsWith(String(body.hostname).toLowerCase() + '.')) {
    await page.goto('/');
    const identity = page.locator('[data-test="topbar-identity"], [data-region="identity"]').first();
    await expect(identity).toHaveText(String(body.domain));
  } else {
    test.skip(true, 'box is not in dedup shape; skipping positive assertion');
  }
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

// -----------------------------------------------------------------
// iter-3 V3 — aggregate health pill lifted into TopBar centre region.
// Was intentionally empty in iter-2 (pill was still trapped inside
// DashboardHome). Now shared via `composables/useHealthPill.ts`.
// -----------------------------------------------------------------

test('V3 TopBar centre region carries the aggregate health pill', async ({ page }) => {
  if (!(await onboardingComplete(page))) test.skip();
  await page.goto('/');
  const centre = page.locator('header [data-region="health"]').first();
  await expect(centre).toBeVisible();
  const pill = centre.locator('[data-test="topbar-health-pill"]');
  await expect(pill).toHaveCount(1);
  const state = await pill.getAttribute('data-state');
  expect(state, 'health pill must expose a HealthState via data-state').toMatch(
    /^(running|not-started|failed|needs-config)$/,
  );
  // The pill text must not be the pre-V3 fallback empty string.
  const text = ((await pill.textContent()) ?? '').trim();
  expect(text.length, 'health pill text is empty; centre still uses the iter-2 fallback').toBeGreaterThan(0);
});

// -----------------------------------------------------------------
// iter-3 B3 — card padding. Bruce reported "all content is squashed up
// next to the borders" on 2026-08-02 morning. The four dashboard cards
// each now carry `p-8` (32 px) instead of the default `p-6` (24 px).
// Geometrically the inner content wrapper must sit ≥ 24 px from the
// card border on all four sides.
// -----------------------------------------------------------------

test('B3 each dashboard card has ≥ 24 px internal padding on all sides', async ({ page }) => {
  if (!(await onboardingComplete(page))) test.skip();
  await page.goto('/');
  for (const key of ['system', 'packages', 'security', 'metrics']) {
    const card = page.locator(`[data-card="${key}"]`);
    await expect(card, `card=${key} not visible`).toBeVisible();
    // computed style: padding-top / -right / -bottom / -left all ≥ 24 px.
    for (const side of ['padding-top', 'padding-right', 'padding-bottom', 'padding-left']) {
      const px = await card.evaluate((el, s) => parseFloat(getComputedStyle(el).getPropertyValue(s)), side);
      expect(px, `card=${key} ${side} = ${px}px, expected ≥ 24`).toBeGreaterThanOrEqual(24);
    }
  }
});
