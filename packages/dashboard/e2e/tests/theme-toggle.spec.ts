import { test, expect } from '@playwright/test';

/**
 * iter-3 V2 — dark mode toggle.
 *
 * The theme composable (composables/useTheme.ts) reads localStorage
 * first, then falls back to `prefers-color-scheme`. TopBar renders
 * a sun/moon button in the user region. `[data-theme="dark"]` on <html>
 * flips the CSS custom properties defined in assets/main.css.
 *
 * The primary assertions here run without auth because the theme
 * scaffold is loaded on every page (the composable's side effects run
 * at module import time). The toggle-button assertions self-skip if
 * the TopBar isn't mounted (fresh e2e box behind auth).
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

test('V2 data-theme attribute is set on <html> from the very first render', async ({ page }) => {
  await page.goto('/login');
  const html = page.locator('html');
  const attr = await html.getAttribute('data-theme');
  expect(attr === 'light' || attr === 'dark', `data-theme=${attr}; must be light or dark`).toBeTruthy();
});

test('V2 dark palette overrides tokens when data-theme=dark', async ({ page }) => {
  await page.goto('/login');
  // Force dark then read a token via getComputedStyle.
  await page.evaluate(() => document.documentElement.setAttribute('data-theme', 'dark'));
  const canvas = await page.evaluate(() =>
    getComputedStyle(document.documentElement).getPropertyValue('--color-canvas').trim(),
  );
  const ink = await page.evaluate(() =>
    getComputedStyle(document.documentElement).getPropertyValue('--color-ink').trim(),
  );
  // Dark canvas is near-black; dark ink is near-white. Assertion is
  // shape (dark palette wired), not exact hex, so the palette can be
  // retuned without breaking the test.
  expect(canvas.toLowerCase()).not.toBe('#faf9f6');
  expect(ink.toLowerCase()).not.toBe('#1a1a1a');
  // Sanity: canvas value length matches a hex or oklch string.
  expect(canvas.length, `canvas token empty (data-theme=dark not wired?)`).toBeGreaterThan(0);
  expect(ink.length).toBeGreaterThan(0);
});

test('V2 toggle in TopBar flips data-theme and persists to localStorage', async ({ page }) => {
  if (!(await onboardingComplete(page))) {
    test.skip(true, 'TopBar only mounts inside auth-guarded AppShell; BL5 fixture will unlock this');
  }
  await page.goto('/');
  const toggle = page.locator('[data-test="theme-toggle"]');
  await expect(toggle).toBeVisible();
  const before = await page.evaluate(() => document.documentElement.getAttribute('data-theme'));
  await toggle.click();
  const after = await page.evaluate(() => document.documentElement.getAttribute('data-theme'));
  expect(after).not.toBe(before);
  const stored = await page.evaluate(() => window.localStorage.getItem('auroraTheme'));
  expect(stored).toBe(after);
});
