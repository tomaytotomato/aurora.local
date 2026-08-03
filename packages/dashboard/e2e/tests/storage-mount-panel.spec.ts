import { test, expect } from '@playwright/test';

/**
 * iter-3 BL3 — per-OS mount panels for storage.
 *
 * Renders a tab set (macOS / Windows / iOS / Android) with copyable
 * smb:// URLs and UNC paths behind a "How to mount" toggle on the
 * storage checklist row. Only appears when the storage row is
 * `state: running`. QR codes for iOS/Android are deferred (task file
 * mentions qrcode-svg but installing a new npm dep costs a Dockerfile
 * rebuild we're not spending this iteration).
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

test('BL3 storage row exposes a "How to mount" toggle when running', async ({ page }) => {
  if (!(await onboardingComplete(page))) test.skip();
  await page.goto('/');
  const storageRow = page.locator('[data-row="storage"]').first();
  // If storage isn't among the enabled packages on the e2e box, skip.
  if ((await storageRow.count()) === 0) test.skip();
  await expect(storageRow).toBeVisible();
  // Toggle only appears when the row is in the running state.
  const status = await storageRow.locator('[data-status]').first().getAttribute('data-status');
  if (status !== 'running') test.skip(true, `storage row state=${status}; toggle only shows when running`);
  const toggle = storageRow.locator('[data-test="storage-mount-toggle"]');
  await expect(toggle).toBeVisible();
  await expect(toggle).toHaveText(/How to mount/i);
});

test('BL3 mount panel renders 4 OS tabs with copyable commands', async ({ page }) => {
  if (!(await onboardingComplete(page))) test.skip();
  await page.goto('/');
  const storageRow = page.locator('[data-row="storage"]').first();
  if ((await storageRow.count()) === 0) test.skip();
  const status = await storageRow.locator('[data-status]').first().getAttribute('data-status');
  if (status !== 'running') test.skip();

  const toggle = storageRow.locator('[data-test="storage-mount-toggle"]');
  await toggle.click();

  const panel = storageRow.locator('[data-test="storage-mount-panel"]');
  await expect(panel).toBeVisible();

  const tabs = panel.locator('[role="tab"]');
  await expect(tabs).toHaveCount(4);

  // Cycle every tab and prove each renders a panel + at least one Copy button.
  for (const tabKey of ['mac', 'windows', 'ios', 'android']) {
    await panel.locator(`[data-tab="${tabKey}"]`).click();
    const openPanel = panel.locator(`[data-panel="${tabKey}"]`);
    await expect(openPanel, `${tabKey} panel not rendered`).toBeVisible();
    await expect(
      openPanel.locator('[data-test="storage-mount-copy"]'),
      `${tabKey} panel is missing the Copy button`,
    ).toBeVisible();
  }
});
