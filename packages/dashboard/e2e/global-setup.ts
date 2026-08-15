import { chromium, request, type FullConfig } from '@playwright/test';
import { execSync } from 'node:child_process';
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';

/**
 * iter-3 BL5 — global E2E setup with an auth fixture.
 *
 * Ordering:
 *   1. Reset the isolated aurora-e2e project (unchanged behaviour).
 *   2. Wait for the backend to answer on :8091.
 *   3. Seed an admin user via POST /api/onboarding/admin.
 *   4. Mark onboarding complete via POST /api/onboarding/complete.
 *   5. Log in via POST /api/auth/login and persist the session-cookie
 *      storage state to fixtures/authed-state.json.
 *
 * Individual specs opt in via `test.use({ storageState: 'fixtures/authed-state.json' })`
 * or by adding it globally in `playwright.config.ts::use.storageState`.
 * Specs that need the pre-auth (fresh box) state instead set
 * `test.use({ storageState: { cookies: [], origins: [] } })`.
 *
 * Skipped-reset mode (`AURORA_E2E_SKIP_RESET=1`) still tries to re-seed
 * the auth cookie against whatever is already up, so iterating on a
 * single spec against a warm box doesn't lose the session.
 *
 * Real-box mode (`AURORA_E2E_FRESH_BOX=1`) is for running against a
 * target that has no reset endpoint at all — a testbed VM running the
 * real install chain, not the isolated aurora-e2e compose project.
 * `/api/onboarding/reset` is gated on `AURORA_E2E=1` server-side and a
 * real box never sets that, so once this file's own seedAuthFixture()
 * creates an admin and marks onboarding complete, nothing in the suite
 * can ever undo it — the box is one-shot. This mode skips seeding
 * entirely (no admin created, no /complete call) and writes an empty,
 * unauthenticated storage state instead, so the wizard specs can walk
 * a genuinely fresh box exactly once. Every other spec that expects
 * the authed fixture will self-skip (they already do — see
 * `onboardingComplete()` guards in dashboard-home-polish.spec.ts and
 * friends) until a later run re-points at a box that has since been
 * onboarded normally.
 */

const BASE_URL = process.env.AURORA_E2E_BASE_URL ?? 'http://localhost:8091';
const ADMIN_USER = process.env.AURORA_E2E_ADMIN_USER ?? 'bruce';
const ADMIN_PASS = process.env.AURORA_E2E_ADMIN_PASS ?? 'aurora-e2e-p@ssw0rd!x';
const STATE_FILE = path.resolve(__dirname, 'fixtures/authed-state.json');

async function waitFor(url: string, timeoutMs = 60_000): Promise<void> {
  const api = await request.newContext();
  const start = Date.now();
  let lastErr = '';
  while (Date.now() - start < timeoutMs) {
    try {
      const res = await api.get(`${url}/api/onboarding/env`);
      if (res.ok()) { await api.dispose(); return; }
      lastErr = `HTTP ${res.status()}`;
    } catch (e) {
      lastErr = (e as Error).message;
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  await api.dispose();
  throw new Error(`aurora-e2e never came up at ${url}: ${lastErr}`);
}

async function seedAuthFixture(): Promise<void> {
  console.log(`[e2e/auth] seeding admin ${ADMIN_USER} + session cookie at ${BASE_URL}`);
  const api = await request.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });

  // Step 1: create admin (idempotent — 409 if already exists).
  const admin = await api.post('/api/onboarding/admin', {
    data: { username: ADMIN_USER, password: ADMIN_PASS, tz: 'UTC' },
  });
  if (!admin.ok() && admin.status() !== 409) {
    throw new Error(`admin create failed: ${admin.status()} ${await admin.text()}`);
  }

  // Step 2: mark onboarding complete (409 if already complete → fine).
  const done = await api.post('/api/onboarding/complete');
  if (!done.ok() && done.status() !== 409) {
    console.warn(`[e2e/auth] /onboarding/complete → ${done.status()} ${await done.text()}`);
  }

  await api.dispose();

  // Step 3: log in via a browser context so Playwright captures the
  // session cookie in a storageState file. Same context is used to save.
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ baseURL: BASE_URL });
  const login = await ctx.request.post('/api/auth/login', {
    data: { username: ADMIN_USER, password: ADMIN_PASS },
  });
  if (!login.ok()) {
    const body = await login.text();
    await browser.close();
    throw new Error(`login failed: ${login.status()} ${body}`);
  }
  if (!existsSync(path.dirname(STATE_FILE))) mkdirSync(path.dirname(STATE_FILE), { recursive: true });
  await ctx.storageState({ path: STATE_FILE });
  await browser.close();
  console.log(`[e2e/auth] storage state saved → ${STATE_FILE}`);
}

export default async function globalSetup(_config: FullConfig): Promise<void> {
  const freshBox = process.env.AURORA_E2E_FRESH_BOX === '1';

  if (process.env.AURORA_E2E_SKIP_RESET !== '1' && !freshBox) {
    const script = path.resolve(__dirname, 'scripts/reset-aurora-e2e.sh');
    console.log(`[e2e] running ${script}`);
    execSync(`bash ${script}`, { stdio: 'inherit' });
  } else {
    console.log('[e2e] AURORA_E2E_SKIP_RESET=1 or AURORA_E2E_FRESH_BOX=1 → skipping reset-aurora-e2e.sh');
  }

  await waitFor(BASE_URL);

  if (freshBox) {
    console.log('[e2e] AURORA_E2E_FRESH_BOX=1 → leaving the box untouched; writing an empty (pre-auth) storage state');
    if (!existsSync(path.dirname(STATE_FILE))) mkdirSync(path.dirname(STATE_FILE), { recursive: true });
    writeFileSync(STATE_FILE, JSON.stringify({ cookies: [], origins: [] }, null, 2));
    return;
  }

  await seedAuthFixture();
}
