import { test, expect } from '@playwright/test';

/**
 * Sanity test — confirms the isolated aurora-e2e instance is reachable
 * and returns a valid /api/health payload. Real onboarding + dashboard
 * flows land in step-3.
 */
test('api/health returns ok', async ({ request }) => {
  const res = await request.get('/api/health');
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  expect(body.status).toBe('ok');
  expect(body.db).toBe(true);
});
