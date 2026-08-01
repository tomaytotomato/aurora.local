import { defineConfig } from '@playwright/test';

/**
 * Aurora dashboard E2E — runs against the isolated aurora-e2e compose
 * project on :8091. Never point this at the live :8090 instance.
 */
export default defineConfig({
  testDir: './tests',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  fullyParallel: false, // tests share one aurora backend + SQLite; serial is safer
  retries: 1,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.AURORA_E2E_BASE_URL ?? 'http://localhost:8091',
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { browserName: 'chromium' },
    },
  ],
});
