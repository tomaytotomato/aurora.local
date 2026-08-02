import { test, expect } from '@playwright/test';

/**
 * iter-3 B4 — Start button poll-until-running.
 *
 * The classic bug this covers: user clicks Start on a multi-container
 * stack (media = 7 containers + first-run image pulls). Backend returns
 * 202 immediately and runs `docker compose up -d` in the background.
 * Before this fix the frontend set a naive `setTimeout(1500ms)` before
 * re-listing packages, so the row flipped through `Starting…` → `Start`
 * button reappeared → a subsequent probe still saw containers as
 * `Created` not `Running` → the user re-clicked → 409 launch-in-progress
 * → catch → `Couldn't start` label with a `Try again` button.
 *
 * The fix: poll `/api/packages` every 2 s until either
 * (a) the target package's `running` bit flips true, or
 * (b) `requires.start_budget_seconds` (from the manifest) elapses. Media
 *     declares 180 s; default is 30 s.
 *
 * These assertions self-skip on the fresh e2e box (no auth fixture yet;
 * BL5 will unlock them). When they do run they exercise the flow against
 * real docker on `:8091`.
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

test('B4 Start button on media polls up to 180 s before flipping to "Couldn\'t start"', async ({ page }) => {
  if (!(await onboardingComplete(page))) {
    test.skip(true, 'no auth fixture yet; BL5 will unlock this run against real docker on :8091');
  }
  await page.goto('/');
  const mediaRow = page.locator('[data-package="media"]').first();
  await expect(mediaRow).toBeVisible();
  // Snapshot the "Starting…" label as soon as the click lands.
  const startBtn = mediaRow.getByRole('button', { name: /start/i });
  await startBtn.click();
  await expect(mediaRow).toContainText(/starting/i, { timeout: 5_000 });

  // Give real compose time to bring up the 7 containers, then either
  // (a) row goes green with a "Running" label, or (b) budget elapses and
  // we see the retry CTA — but critically NOT until the budget has run.
  const runningLabel = mediaRow.locator('text=/^Running$/i');
  const retryBtn = mediaRow.locator('[data-cta="retry"]');
  const raceStart = Date.now();
  await expect(async () => {
    const isRunning = await runningLabel.isVisible().catch(() => false);
    const isRetry = await retryBtn.isVisible().catch(() => false);
    expect(isRunning || isRetry, 'expected Running label or retry CTA').toBeTruthy();
  }).toPass({ timeout: 200_000 });

  const elapsedMs = Date.now() - raceStart;
  // If we ended in retry, the budget must have been honoured (≥ 150 s).
  const endedInRetry = await retryBtn.isVisible().catch(() => false);
  if (endedInRetry) {
    expect(elapsedMs, 'retry CTA appeared before the 180 s budget elapsed').toBeGreaterThan(150_000);
  }
});

test('B4 Packages card count reflects `running` boolean, not the ghost `.status` field', async ({ page }) => {
  if (!(await onboardingComplete(page))) test.skip();
  await page.goto('/');
  const card = page.locator('[data-card="packages"]');
  await expect(card).toBeVisible();
  const count = card.locator('[data-test="packages-count"]');
  // Post-B1 (core probe.kind:self) + B4 (frontend reads .running):
  // the count must NEVER read "N enabled · 0 running" when core is up,
  // because core is now always running per PackagesService.parseManifest.
  const text = (await count.textContent()) ?? '';
  expect(text).not.toMatch(/enabled\s*[\u00b7\u2022\-]\s*0\s+running/i);
});
