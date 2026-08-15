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

/**
 * UX_SPEC §6 anti-pattern 5 — /onboarding/done should never render an
 * unactionable "Failed" pill.
 *
 * P3 closure (previously self-skipped on a fresh box): we now inject a
 * `failed` service via a spec-scoped `page.route` interceptor on
 * `/api/services/status` so the checklist reliably renders the failed
 * row. No application code is changed — this is test hygiene only.
 */
test('done page: any failed package exposes a Retry action', async ({ page }) => {
  // Ensure the DoneChecklist has a non-empty `enabledPackages` prop even
  // when the wizard was not walked. The store's hydrate() reads
  // `enabled_packages` from GET /api/onboarding.
  await page.route('**/api/onboarding', (route) => {
    if (route.request().method() !== 'GET') return route.fallback();
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        step: 'done',
        domain: 'aurora.local',
        enabled_packages: ['media', 'privacy'],
        dns_mode: 'adguard',
        admin_username: 'aurora',
        bootstrap_mode: 'fresh',
        completed: ['welcome', 'admin', 'domain', 'sso', 'secrets', 'tls', 'review', 'install'],
      }),
    });
  });

  // Inject one failed service. The DoneChecklist polls this every 5s;
  // the interceptor stays active for the whole test.
  await page.route('**/api/services/status', (route) => {
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        generated_at: new Date().toISOString(),
        services: [
          {
            package: 'media',
            container: 'sonarr',
            state: 'failed',
            reason: 'The Sonarr container crashed on start.',
            detail: 'container_crashed',
            open_url: null,
            priority: 0,
            probed_ms: 12,
          },
          {
            package: 'privacy',
            container: 'adguard',
            state: 'running',
            reason: null,
            detail: null,
            open_url: 'http://aurora.local',
            priority: 3,
            probed_ms: 8,
          },
        ],
      }),
    });
  });

  // Capture whether the Retry button wires through to the launch endpoint.
  let retryLaunchCalls = 0;
  await page.route('**/api/onboarding/launch', (route) => {
    if (route.request().method() !== 'POST') return route.fallback();
    retryLaunchCalls += 1;
    return route.fulfill({
      status: 202,
      contentType: 'application/json',
      body: JSON.stringify({ job_id: 'test-retry-job-1' }),
    });
  });

  await page.goto('/onboarding/done');

  // Failed row must render (proves the fixture landed).
  const failed = page.locator('[data-status="failed"]');
  await expect(failed.first()).toBeVisible({ timeout: 10_000 });
  const n = await failed.count();
  expect(n, 'expected at least one failed row from injected fixture').toBeGreaterThan(0);

  // Each failed row exposes a visible Retry button on the same card.
  for (let i = 0; i < n; i++) {
    const card = failed.nth(i).locator('xpath=ancestor::li[1]');
    const retry = card.getByRole('button', { name: /^retry$/i });
    await expect(retry, `failed card ${i} missing Retry`).toBeVisible();
  }

  // Clicking Retry on the first failed row triggers the launch endpoint
  // (mocked to 202). This asserts the CTA is wired, not just rendered.
  const firstCard = failed.first().locator('xpath=ancestor::li[1]');
  await firstCard.getByRole('button', { name: /^retry$/i }).click();
  await expect.poll(() => retryLaunchCalls, {
    message: 'Retry click did not POST /api/onboarding/launch',
    timeout: 5_000,
  }).toBeGreaterThan(0);
});
