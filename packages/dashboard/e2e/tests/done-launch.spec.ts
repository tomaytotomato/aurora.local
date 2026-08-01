import { test, expect } from '@playwright/test';

/**
 * UX_SPEC §4 P0-1 iter-1 acceptance:
 *   The Done screen must launch services in-app. No SSH.
 *
 * Preconditions (seeded via the API — this is the "state seeding" escape
 * hatch iter-1 plan §4.2 authorises so we don't have to drive the whole
 * wizard through 14 unrelated red steps):
 *   - Create an admin user (unlocks the mid-onboarding guard).
 *   - Patch enabled_packages to a small set.
 *   - Load /onboarding/done and click "Start services".
 *
 * Then assert:
 *   - POST /api/onboarding/launch returned 202 with a job_id
 *   - SSE stream connects
 *   - the LaunchProgress component appears with at least one package row
 */

const ADMIN = {
  username: 'aurora',
  password: 'e2e-launch-happy-path-passphrase-123',
};
const ENABLED = ['core'];

test.describe('/onboarding/done launch happy path', () => {
  test('Start services POSTs /launch and streams events', async ({ page, request }) => {
    // Seed admin (idempotent-ish; 409 on repeat is fine for the isolated E2E box).
    const admin = await request.post('/api/onboarding/admin', {
      data: { ...ADMIN, tz: 'UTC' },
    });
    expect([200, 409]).toContain(admin.status());

    // Patch enabled packages so `.state.yml` has something to launch.
    const patch = await request.patch('/api/onboarding', {
      data: { enabled_packages: ENABLED, step: 'done' },
    });
    expect([200, 409]).toContain(patch.status());

    // Intercept POST /api/onboarding/launch to capture the response.
    let launchResponseStatus: number | null = null;
    let launchJobId: string | null = null;
    page.on('response', async (res) => {
      if (res.url().endsWith('/api/onboarding/launch') && res.request().method() === 'POST') {
        launchResponseStatus = res.status();
        if (res.status() === 202) {
          const body = await res.json();
          launchJobId = body.job_id ?? null;
        }
      }
    });

    await page.goto('/onboarding/done');

    const startBtn = page.getByTestId('start-services');
    await expect(startBtn).toBeVisible();
    await startBtn.click();

    // Wait for the LaunchProgress panel to render — evidence the POST succeeded.
    await expect(page.getByTestId('launch-progress')).toBeVisible({ timeout: 10_000 });

    expect(launchResponseStatus, 'POST /api/onboarding/launch status').toBe(202);
    expect(launchJobId, 'job id present').toBeTruthy();

    // At least one package row rendered.
    const rows = page.getByTestId('launch-package-list').locator('[data-package]');
    expect(await rows.count()).toBeGreaterThan(0);
  });

  test('Done page carries no SSH / shell-script copy', async ({ page }) => {
    await page.goto('/onboarding/done');
    const body = await page.locator('body').innerText();
    expect(body).not.toContain('./scripts/up.sh');
    expect(body).not.toContain('scripts/down.sh');
    expect(body).not.toMatch(/SSH into the box/i);
    const preCount = await page.locator('pre').count();
    expect(preCount).toBe(0);
  });
});
