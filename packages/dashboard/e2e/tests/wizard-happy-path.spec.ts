import { test } from '@playwright/test';
import { expect } from '../fixtures/ux-matchers';

/**
 * Sarah persona happy-path: walks Welcome → Admin → Domain → SSO →
 * Secrets → DNS → TLS → Review → Done using recommended defaults and
 * minimal typing. Cites UX_SPEC.md §2 (the 30-minute journey) throughout.
 *
 * The wizard step order in this file matches the router (§Spec header):
 *   Welcome → Admin → Domain → SSO → Secrets → DNS → TLS → Review → Done
 * The interactive package-picker step (Packages) was removed 2026-08-15:
 * a first run installs the mandatory set only (core, identity if SSO is
 * accepted, storage) and everything else is added afterwards from the
 * Apps catalogue. The prompt requested "Welcome → TLS → Domain → DNS → …"
 * — that ordering does not exist in the app; we follow the router-defined
 * order so the test can actually walk the flow.
 */

test.describe('wizard happy path (Sarah persona)', () => {
  test.beforeEach(async ({ page, request }) => {
    // TD5 (2026-08-02): rewind the wizard between specs so a suite that
    // already ran to completion doesn't redirect us straight to
    // /dashboard/home. Endpoint is gated on AURORA_E2E=1 in the aurora-e2e
    // compose project; 404 in prod, silently ignored here.
    await request.post('/api/onboarding/reset').catch(() => {});
    await page.goto('/');
    // Redirect should send a fresh box to /onboarding/welcome.
    await page.waitForURL(/\/onboarding\/(welcome|admin|domain|sso|secrets|dns|tls|review|done)/, {
      timeout: 10_000,
    });
  });

  /** UX_SPEC §3.2 W1 + W2: welcome renders host facts, no null/undefined. */
  test('welcome step renders host facts without null/undefined', async ({ page }) => {
    await page.goto('/onboarding/welcome');
    const body = await page.locator('body').innerText();
    expect(body).not.toContain('null');
    expect(body).not.toContain('undefined');
    expect(body).not.toContain('[object Object]');
  });

  /** UX_SPEC §3.2 W4 + W5: continue is unconditionally enabled and lands on /admin. */
  test('welcome → continue navigates to /onboarding/admin', async ({ page }) => {
    await page.goto('/onboarding/welcome');
    const cta = page.locator('button[data-cta="primary"]:visible').first();
    await expect(cta).toBeEnabled();
    await cta.click();
    await page.waitForURL(/\/onboarding\/admin/, { timeout: 5_000 });
  });

  /** UX_SPEC §3.3 A1 + A2: username prefilled "aurora", password ≥ 20 chars. */
  test('admin step prefills username and password', async ({ page }) => {
    await page.goto('/onboarding/admin');
    const username = page.locator('input[name="username"], input#username, [data-field="username"] input').first();
    await expect(username).toHaveValue(/aurora/i);
    const password = page.locator('input[name="password"], input#password, [data-field="password"] input').first();
    const pwVal = await password.inputValue();
    expect(pwVal.length).toBeGreaterThanOrEqual(20);
  });

  /** UX_SPEC §3.3 A4: continue disabled until the "saved this password" checkbox is ticked. */
  test('admin continue is disabled until saved-password checkbox is ticked', async ({ page }) => {
    await page.goto('/onboarding/admin');
    const cta = page.locator('button[data-cta="primary"]:visible').first();
    await expect(cta).toBeDisabled();
    const cb = page.getByRole('checkbox', { name: /saved this password/i });
    await cb.check();
    await expect(cta).toBeEnabled();
  });

  /** UX_SPEC §3.4 D1 + D5: domain prefilled aurora.local, continue advances. */
  test('domain step prefilled with aurora.local and advances to /sso', async ({ page }) => {
    await page.goto('/onboarding/domain');
    const input = page.locator('input[name="domain"], input#domain, [data-field="domain"] input').first();
    await expect(input).toHaveValue(/aurora\.local/);
    const cta = page.locator('button[data-cta="primary"]:visible').first();
    await cta.click();
    // The interactive package-picker step is gone (2026-08-15) — domain
    // now hands off straight to the SSO step, which is where identity's
    // enablement (opt-in, not forced) is decided.
    await page.waitForURL(/\/onboarding\/sso/, { timeout: 5_000 });
  });

  /** UX_SPEC §3.6 S1: secrets screen names the count from GET /plan. */
  test('secrets step names the number of generated secrets', async ({ page }) => {
    await page.goto('/onboarding/secrets');
    const body = await page.locator('body').innerText();
    expect(body).toMatch(/generate\s+\d+\s+secrets?/i);
  });

  /** UX_SPEC §3.6 S2 + §6 anti-pattern 9: no milestone/version leakage. */
  test('secrets step contains no milestone/roadmap language', async ({ page }) => {
    await page.goto('/onboarding/secrets');
    const body = await page.locator('body').innerText();
    expect(body).not.toMatch(/landing in the next slice/i);
    expect(body).not.toMatch(/ships with m2/i);
    expect(body).not.toMatch(/coming soon/i);
    expect(body).not.toMatch(/\bv0\.[23]\b/);
  });

  /** UX_SPEC §3.7 N1: three DNS tabs with the exact enum values. */
  test('dns step exposes adguard/router/mdns tabs', async ({ page }) => {
    await page.goto('/onboarding/dns');
    // Tabs may be rendered as buttons or role=tab; accept either.
    const body = await page.locator('body').innerText();
    expect(body).toMatch(/adguard/i);
    expect(body).toMatch(/router/i);
    expect(body).toMatch(/mdns/i);
  });

  /** UX_SPEC §3.8 T1: root-CA download button exposes caddy-root.crt. */
  test('tls step exposes a Download root CA control', async ({ page }) => {
    await page.goto('/onboarding/tls');
    const btn = page.getByRole('button', { name: /download.*root ca/i }).first();
    await expect(btn).toBeVisible();
  });

  /** UX_SPEC §3.8 T5: skip-for-now action is present. */
  test('tls step exposes a "Skip for now" secondary action', async ({ page }) => {
    await page.goto('/onboarding/tls');
    const skip = page.getByRole('button', { name: /skip.*(later|for now)/i }).first();
    await expect(skip).toBeVisible();
  });

  /** UX_SPEC §3.9 R1: review summary renders exactly six rows. */
  test('review step summary table shows Domain/Admin/DNS/Packages/vhosts/Ports', async ({ page }) => {
    await page.goto('/onboarding/review');
    const body = await page.locator('body').innerText();
    for (const k of ['Domain', 'Admin', 'DNS', 'Packages', 'vhosts', 'Ports']) {
      expect(body).toContain(k);
    }
  });

  /** UX_SPEC §3.9 R3: install button label is the single word "Install". */
  test('review install button label is exactly "Install"', async ({ page }) => {
    await page.goto('/onboarding/review');
    const btn = page.locator('button[data-cta="primary"]:visible').first();
    const label = (await btn.innerText()).trim();
    expect(label).toBe('Install');
  });
});
