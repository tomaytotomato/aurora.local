import { expect as baseExpect, type Page } from '@playwright/test';

/**
 * Custom matchers implementing the UX_SPEC §7 test harness scaffolding.
 * Every wizard test can reuse these instead of open-coding the regex.
 *
 * SHELL_TEXT_RE — mirrors the regex in UX_SPEC §3:
 *   /\b(sudo|ssh|scp|cd\s+~|\.\/scripts\/|docker\s+compose|apt(-get)?|systemctl|curl\s+\|)\b/
 */
export const SHELL_TEXT_RE =
  /\b(sudo|ssh|scp|cd\s+~|\.\/scripts\/|docker\s+compose|apt(-get)?|systemctl|curl\s+\|)\b/;

/**
 * Broader banned-jargon list from UX_SPEC §3.1 G4 + §6 anti-pattern 8.
 * Only used by the no-cli-instructions spec (not every matcher call) so
 * that legit copy like "container" in a doc link isn't blanket banned.
 */
export const CLI_WORDS_RE = /\b(SSH|ssh|terminal|command line|shell|CLI|PowerShell)\b/;

export const expect = baseExpect.extend({
  /**
   * G2 + G3: no <pre> anywhere in the wizard, and no <code> element whose
   * text matches the shell-text regex.
   */
  async toHaveNoPreOrShell(page: Page) {
    const preCount = await page.locator('pre').count();
    if (preCount > 0) {
      // Allow the single install-log exception on /onboarding/review if
      // it's a <div role="log"> (per G2). Any real <pre> is a fail.
      return {
        pass: false,
        message: () =>
          `expected no <pre> in wizard DOM, found ${preCount} (UX_SPEC G2)`,
      };
    }
    const codeTexts = await page.locator('code').allTextContents();
    const bad = codeTexts.filter((t) => SHELL_TEXT_RE.test(t));
    if (bad.length > 0) {
      return {
        pass: false,
        message: () =>
          `expected no <code> with shell text (UX_SPEC G3), found:\n  ` +
          bad.map((t) => JSON.stringify(t)).join('\n  '),
      };
    }
    return { pass: true, message: () => 'no <pre> or shell <code>' };
  },

  /**
   * G5: exactly one primary CTA (button[data-cta="primary"]) visible.
   */
  async toHaveOneVisiblePrimary(page: Page) {
    const primaries = page.locator('button[data-cta="primary"]:visible');
    const count = await primaries.count();
    if (count !== 1) {
      return {
        pass: false,
        message: () =>
          `expected exactly 1 visible primary CTA (UX_SPEC G5), found ${count}`,
      };
    }
    return { pass: true, message: () => 'one visible primary CTA' };
  },

  /**
   * G9: hard refresh preserves URL and hydrated fields, no network-error
   * alert visible after reload.
   */
  async toSurviveReload(page: Page) {
    const urlBefore = page.url();
    await page.reload({ waitUntil: 'networkidle' });
    const urlAfter = page.url();
    if (urlBefore !== urlAfter) {
      return {
        pass: false,
        message: () =>
          `expected URL to survive reload (UX_SPEC G9): ${urlBefore} → ${urlAfter}`,
      };
    }
    const err = page.locator('[data-tone="err"]:visible, [role="alert"]:visible');
    const errCount = await err.count();
    if (errCount > 0) {
      return {
        pass: false,
        message: () =>
          `expected no error alert after reload (UX_SPEC G9), found ${errCount}`,
      };
    }
    return { pass: true, message: () => 'route survives reload' };
  },
});
