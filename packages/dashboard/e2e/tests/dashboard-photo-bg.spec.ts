import { test, expect } from '@playwright/test';

/**
 * iter-3 V1 — aurora photo background under the bento grid.
 *
 * The AppShell reads `route.meta.photoBg` and renders `<AuroraBackground>`
 * fixed at z-index 0 when true. Only DashboardHome opts in for iter-3.
 * Assertion runs against the pre-auth login screen too so it doesn't
 * self-skip on the fresh e2e box: we assert that the aurora photo layer
 * IS present on `/` when authenticated but is absent on `/login`.
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

test('V1 login screen renders the aurora photo background', async ({ page }) => {
  await page.goto('/login');
  // Login-polish (2026-08-02): LoginView now mounts AuroraBackground
  // directly (parity with /dashboard/home and /onboarding/welcome) so the
  // layer must be present regardless of auth state.
  const layer = page.locator('.aurora-bg');
  await expect(layer).toHaveCount(1);
});

test('V1 authenticated /dashboard/home renders the aurora photo background', async ({ page }) => {
  if (!(await onboardingComplete(page))) {
    test.skip(true, 'AppShell only mounts inside auth-guarded routes; BL5 fixture will unlock this');
  }
  await page.goto('/');
  const layer = page.locator('.aurora-bg');
  await expect(layer).toHaveCount(1);
  const img = layer.locator('img.photo');
  await expect(img).toHaveAttribute('src', /^\/aurora\/[1-5]\.jpg$/);
  // Credit bubble must be present + accessible.
  const credit = page.locator('a.credit');
  await expect(credit).toBeVisible();
  await expect(credit).toHaveAttribute('rel', /noopener/);
});
