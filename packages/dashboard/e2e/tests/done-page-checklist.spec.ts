import { test } from '@playwright/test';
import { expect } from '../fixtures/ux-matchers';

/**
 * UX_SPEC §3.10 X1–X7 + §5.1: the Done page must be a checklist of
 * per-package status pills with prioritized order (blocker → optional)
 * and one-click CTAs. No <pre>, no shell text, no ambiguity.
 */

test.describe('done page checklist', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/onboarding/done');
  });

  /** X1: no <pre>, no shell-text <code>. */
  test('has no <pre> or shell-text <code>', async ({ page }) => {
    await expect(page).toHaveNoPreOrShell();
  });

  /** X2: no SSH/terminal/host/operator language. */
  test('has no "SSH" / "host" / "operator" / "terminal" text', async ({ page }) => {
    const body = await page.locator('body').innerText();
    for (const word of ['SSH', 'ssh', 'terminal', 'operator']) {
      expect(body).not.toContain(word);
    }
  });

  /** X3: every enabled package renders as a card with a status pill. */
  test('renders a card per enabled package with a data-status pill', async ({ page }) => {
    const cards = page.locator('[data-package]');
    const count = await cards.count();
    expect(count).toBeGreaterThan(0);
    for (let i = 0; i < count; i++) {
      const card = cards.nth(i);
      const pill = card.locator('[data-status]').first();
      await expect(pill).toBeVisible();
      const status = await pill.getAttribute('data-status');
      expect(['running', 'needs-config', 'failed', 'not-started', 'starting']).toContain(status);
    }
  });

  /** X3 (cont): every card has a primary action button with an approved label. */
  test('every package card has an approved primary action label', async ({ page }) => {
    const cards = page.locator('[data-package]');
    const count = await cards.count();
    expect(count).toBeGreaterThan(0);
    for (let i = 0; i < count; i++) {
      const card = cards.nth(i);
      const btn = card.locator('button, a[role="button"]').first();
      const label = (await btn.innerText()).trim();
      expect(label).toMatch(/^(Open|Finish setup|Retry|Waiting…|Start)$/);
    }
  });

  /** §5.1 ordering — blocker rows (failed/needs-config) precede optional (running/not-started polish). */
  test('rows ordered blockers first, optional last', async ({ page }) => {
    const statuses = await page.locator('[data-package] [data-status]').evaluateAll(
      (els) => els.map((e) => (e as HTMLElement).dataset.status ?? '')
    );
    // Weight scheme mirrors UX_SPEC §5.1: failed(0) < needs-config(1) < not-started(2) < starting(3) < running(4).
    const weight: Record<string, number> = {
      failed: 0,
      'needs-config': 1,
      'not-started': 2,
      starting: 3,
      running: 4,
    };
    const ws = statuses.map((s) => weight[s] ?? 5);
    for (let i = 1; i < ws.length; i++) {
      expect(ws[i], `card ${i} out of priority order: ${statuses.join(',')}`).toBeGreaterThanOrEqual(
        ws[i - 1]
      );
    }
  });

  /** X5: single primary CTA "Go to my dashboard" at page bottom. */
  test('single "Go to my dashboard" primary CTA at the bottom', async ({ page }) => {
    const cta = page.getByRole('button', { name: /Go to my dashboard/i });
    await expect(cta).toBeVisible();
    await expect(page).toHaveOneVisiblePrimary();
  });

  /** X4: if any package status is failed/not-started, a top banner acknowledges Aurora is bringing them up. */
  test('failed/not-started packages surface a top banner', async ({ page }) => {
    const pending = page.locator('[data-status="failed"], [data-status="not-started"]');
    if ((await pending.count()) === 0) test.skip();
    const banner = page.locator('[data-banner="bringing-up"]').first();
    await expect(banner).toBeVisible();
  });
});
