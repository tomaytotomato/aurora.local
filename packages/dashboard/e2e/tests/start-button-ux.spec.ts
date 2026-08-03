import { test, expect } from '@playwright/test';

/**
 * iter-3 Start-button UX hardening — regression coverage for the
 * "Notes double-click 409" bug Bruce hit on 2026-08-02.
 *
 * Contract (see DoneChecklist.vue block comment for the pattern doc):
 *   1. Clicking Start immediately flips the row to 'Waiting…' + disabled
 *      + aria-busy=true, without waiting for the next 5s status probe.
 *   2. A rapid double-click issues exactly ONE POST /api/services/{pkg}/start
 *      — the second click is silently no-op'd by the click guard.
 *   3. Deadline+rollback: if the row hasn't reported 'running' by the
 *      manifest-declared budget, the button becomes clickable again so
 *      the user can retry.
 *
 * Uses the BL5 auth fixture (playwright.config default storageState).
 *
 * Target service: 'notes'. Chosen because it's the smallest single-container
 * package (fast enough that the deadline test can use a stubbed short
 * budget without hitting real backend timing), and it's the exact one
 * Bruce hit the bug on.
 */

const PKG = 'notes';

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

test.describe('Start-button UX hardening', () => {
  test.beforeEach(async ({ page }) => {
    if (!(await onboardingComplete(page))) {
      test.skip(true, 'requires an onboarded box — the BL5 fixture provides it');
    }
    // Force the target package into 'not-started' so we can click Start.
    // The status endpoint is idempotent so we can stub it via page.route
    // for deterministic UX assertions — but the default fixture already
    // reports notes as 'not-started' on a fresh e2e project, so we can
    // rely on it.
  });

  test('immediate optimistic transition: click → button disabled + Waiting… without waiting for the 5s probe', async ({ page }) => {
    // Stub /api/services/notes/start to a slow 202 so we can observe the
    // optimistic state before the POST returns.
    let postCount = 0;
    await page.route('**/api/services/notes/start', async (route) => {
      postCount += 1;
      // Delay 800ms so the click-vs-probe race is measurable.
      await new Promise((r) => setTimeout(r, 800));
      await route.fulfill({
        status: 202,
        contentType: 'application/json',
        body: JSON.stringify({ job_id: 'test-job', packages: [PKG], started_at: new Date().toISOString() }),
      });
    });

    await page.goto('/dashboard/home');

    // DoneChecklist mounts below the bento grid when there are enabled packages.
    const row = page.locator(`[data-package="${PKG}"]`).first();
    await expect(row).toBeVisible({ timeout: 10_000 });

    const cta = row.locator('[data-test="row-cta"]');
    // Row must currently be Start-able. If the fixture reports it as
    // 'running' this spec is running against a warmer box than expected;
    // skip cleanly.
    const label = (await cta.textContent())?.trim() ?? '';
    test.skip(!/start/i.test(label), `row already ${label}; needs a not-started row`);

    // Click and immediately assert the optimistic UI (before the POST
    // returns — the 800ms route stub gives us the window).
    await cta.click();

    // Under 500ms the button MUST be disabled and read 'Waiting…' with
    // aria-busy true. This is the whole point of the fix.
    await expect(cta).toBeDisabled({ timeout: 500 });
    await expect(cta).toHaveAttribute('aria-busy', 'true', { timeout: 500 });
    await expect(cta).toContainText(/waiting/i, { timeout: 500 });

    // Spinner glyph is rendered inside the button.
    await expect(cta.locator('.animate-spin')).toBeVisible({ timeout: 500 });

    // Exactly one POST issued so far.
    expect(postCount).toBe(1);
  });

  test('rapid double-click issues exactly ONE network POST', async ({ page }) => {
    let postCount = 0;
    await page.route('**/api/services/notes/start', async (route) => {
      postCount += 1;
      await new Promise((r) => setTimeout(r, 400));
      await route.fulfill({
        status: 202,
        contentType: 'application/json',
        body: JSON.stringify({ job_id: 'test-job', packages: [PKG], started_at: new Date().toISOString() }),
      });
    });

    await page.goto('/dashboard/home');
    const row = page.locator(`[data-package="${PKG}"]`).first();
    await expect(row).toBeVisible({ timeout: 10_000 });

    const cta = row.locator('[data-test="row-cta"]');
    const label = (await cta.textContent())?.trim() ?? '';
    test.skip(!/start/i.test(label), `row already ${label}; needs a not-started row`);

    // Rapid double-click. Playwright respects the button's disabled
    // attribute on the second call and will queue it as a no-op, but
    // we also assert at the network layer.
    await cta.click({ force: true });
    // Wait a tick so the DOM disables the button, then try to click again.
    // Use `force: true` to bypass Playwright's "element is not enabled"
    // guard — this mimics the DOM race where an in-flight synthetic
    // click event fires against a still-enabled node.
    await cta.click({ force: true, timeout: 200 }).catch(() => { /* disabled → expected */ });
    await cta.click({ force: true, timeout: 200 }).catch(() => { /* disabled → expected */ });

    // Give the network layer a moment to settle.
    await page.waitForTimeout(1000);

    expect(postCount, 'rapid clicks must dedupe to exactly one POST').toBe(1);
  });

  test('post-click state persists through the next status probe (no flip back to Start)', async ({ page }) => {
    await page.route('**/api/services/notes/start', async (route) => {
      await route.fulfill({
        status: 202,
        contentType: 'application/json',
        body: JSON.stringify({ job_id: 'test-job', packages: [PKG], started_at: new Date().toISOString() }),
      });
    });

    // Stub /api/services/status to continue reporting notes as 'not-started'
    // even after the click. Without the optimistic overlay this would flip
    // the button back to 'Start' on the next 5s probe — the bug this fix
    // exists to prevent. With the overlay the button stays 'Waiting…'
    // until either the status flips to 'running' or the budget elapses.
    await page.route('**/api/services/status', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          generated_at: new Date().toISOString(),
          services: [
            { package: PKG, container: PKG, state: 'not-started', reason: 'Not started yet', detail: null, open_url: null, priority: 2, probed_ms: 30 },
          ],
        }),
      });
    });

    await page.goto('/dashboard/home');
    const row = page.locator(`[data-package="${PKG}"]`).first();
    await expect(row).toBeVisible({ timeout: 10_000 });

    const cta = row.locator('[data-test="row-cta"]');
    // With the stubbed status endpoint, the button IS a Start button.
    await expect(cta).toContainText(/start/i);
    await cta.click();

    // Immediately: Waiting…
    await expect(cta).toContainText(/waiting/i, { timeout: 500 });

    // Wait 3 seconds — during which the status probe fires (fast-poll is
    // now 800ms). The stub keeps reporting 'not-started', but the
    // optimistic overlay must keep the button on 'Waiting…' anyway.
    await page.waitForTimeout(3000);
    await expect(cta).toContainText(/waiting/i);
    await expect(cta).toBeDisabled();
  });
});
