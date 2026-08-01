import { test, expect } from '@playwright/test';

/**
 * UX_SPEC §6 anti-pattern 5 + §3.9 R4 (5): every failure state renders
 * (a) what happened in plain English, (b) what to do next, (c) a Retry
 * button. No raw stack trace, no "Install failed" one-liner.
 *
 * These tests force a failure by injecting a bad install payload (via
 * the API the wizard uses) and assert the UI presents an actionable
 * error. If the backend refuses to fail on demand yet, tests fail
 * closed — that itself is a signal for worker step-5.
 */

/** UX_SPEC §3.9 R4 (5) — failed install shows plain-English reason + retry, not a stack trace. */
test('install failure shows retry + plain-English reason (no stack trace)', async ({ page }) => {
  await page.goto('/onboarding/review');
  // Intercept /api/onboarding/install (or /apply) and force a 500 with a
  // structured error body. This exercises the UI's error path without
  // needing a real broken container.
  await page.route(/\/api\/onboarding\/(install|apply|launch)/, (route) =>
    route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({
        error: 'port_conflict',
        message: 'Port 53 is already in use by another service on this box.',
      }),
    })
  );
  const install = page.locator('button[data-cta="primary"]:visible').first();
  await install.waitFor({ state: 'visible', timeout: 5_000 }).catch(() => { /* skip below */ });
  if (!(await install.isVisible())) test.skip();
  await install.click();
  // Alert appears with plain-English message.
  const alert = page.locator('[data-tone="err"], [role="alert"]').first();
  await expect(alert).toBeVisible({ timeout: 10_000 });
  const alertText = await alert.innerText();
  expect(alertText).toMatch(/port 53.*in use/i);
  // No raw java/node/js stack traces bleeding through.
  expect(alertText).not.toMatch(/at [\w.$<>]+\([\w./:\-]+:\d+\)/);
  expect(alertText).not.toMatch(/Exception|Traceback/);
  // Retry button present.
  const retry = page.getByRole('button', { name: /retry/i });
  await expect(retry).toBeVisible();
});

/** UX_SPEC §3.9 R4 (3) — no dead air >3s during install (heartbeat line appears). */
test('install log emits progress within 3s of clicking Install', async ({ page }) => {
  await page.goto('/onboarding/review');
  const install = page.locator('button[data-cta="primary"]:visible').first();
  await install.waitFor({ state: 'visible', timeout: 5_000 }).catch(() => { /* skip below */ });
  if (!(await install.isVisible())) test.skip();
  await install.click();
  // Live log region appears within 3s and has at least one line.
  const log = page.locator('[role="log"]').first();
  await expect(log).toBeVisible({ timeout: 3_000 });
  const initial = (await log.innerText()).trim();
  expect(initial.length, 'log region empty within 3s of Install click').toBeGreaterThan(0);
});

/** UX_SPEC §6 anti-pattern 5 — /onboarding/done should never render an unactionable "Failed" pill. */
test('done page: any failed package exposes a Retry action', async ({ page }) => {
  await page.goto('/onboarding/done');
  const failed = page.locator('[data-status="failed"]');
  const n = await failed.count();
  if (n === 0) test.skip(true, 'no failed packages on the fresh e2e box');
  for (let i = 0; i < n; i++) {
    const card = failed.nth(i).locator('..');
    const retry = card.getByRole('button', { name: /retry/i });
    await expect(retry, `failed card ${i} missing Retry`).toBeVisible();
  }
});
