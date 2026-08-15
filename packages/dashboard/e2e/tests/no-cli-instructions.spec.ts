import { test } from '@playwright/test';
import { expect, SHELL_TEXT_RE, CLI_WORDS_RE } from '../fixtures/ux-matchers';

/**
 * P0 principle from UX_SPEC.md §6 anti-pattern 1 + 2:
 *   - No shell commands in the wizard.
 *   - No <pre> blocks with shell text.
 *   - No "SSH" / "terminal" / "command line" / "CLI" copy in casual flow.
 *
 * This is the single most important suite in the project. If it goes red,
 * we shipped a Sarah-deletes-Aurora build.
 */

const WIZARD_STEPS = [
  '/onboarding/welcome',
  '/onboarding/admin',
  '/onboarding/domain',
  '/onboarding/sso',
  '/onboarding/secrets',
  '/onboarding/dns',
  '/onboarding/tls',
  '/onboarding/review',
  '/onboarding/done',
] as const;

// TD5 (2026-08-02): rewind the box before every spec so a suite that
// already ran to completion doesn't redirect /onboarding/* to
// /dashboard/home. Endpoint is gated on AURORA_E2E=1 in the aurora-e2e
// compose project; 404 in prod, silently ignored here.
test.beforeEach(async ({ request }) => {
  await request.post('/api/onboarding/reset').catch(() => {});
});

for (const route of WIZARD_STEPS) {
  /** UX_SPEC §3.1 G2 + G3 — no <pre>, no <code> with shell text. */
  test(`${route}: no <pre> or shell-text <code>`, async ({ page }) => {
    await page.goto(route);
    await expect(page).toHaveNoPreOrShell();
  });

  /** UX_SPEC §3.1 G4 + §6 anti-pattern 1 — no SSH/terminal/CLI in visible copy. */
  test(`${route}: no SSH/terminal/CLI in visible copy`, async ({ page }) => {
    await page.goto(route);
    // innerText excludes hidden elements — matches "visible copy" per G4.
    const bodyText = await page.locator('body').innerText();
    const hits = bodyText.match(CLI_WORDS_RE);
    expect(hits, `visible CLI-word hits on ${route}: ${JSON.stringify(hits)}`).toBeNull();
  });

  /** UX_SPEC §3.1 G4 — no shell-text substrings (sudo, ssh, ./scripts/, …). */
  test(`${route}: no shell-command patterns in visible copy`, async ({ page }) => {
    await page.goto(route);
    const bodyText = await page.locator('body').innerText();
    const hits = bodyText.match(SHELL_TEXT_RE);
    expect(hits, `shell-text hits on ${route}: ${JSON.stringify(hits)}`).toBeNull();
  });
}

/** UX_SPEC §4 P0-1 — the smoking gun: OnboardingDone must not tell Sarah to SSH. */
test('/onboarding/done: does not mention ./scripts/up.sh', async ({ page }) => {
  await page.goto('/onboarding/done');
  const bodyText = await page.locator('body').innerText();
  expect(bodyText).not.toContain('./scripts/up.sh');
  expect(bodyText).not.toContain('scripts/down.sh');
  expect(bodyText).not.toMatch(/SSH into the box/i);
});
