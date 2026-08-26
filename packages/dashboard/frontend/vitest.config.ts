/// <reference types="vitest" />
import { defineConfig } from 'vitest/config';
import vue from '@vitejs/plugin-vue';
import path from 'node:path';

/**
 * iter-34 (v0.3 followup): Vitest bootstrap for FE component / unit
 * coverage. Runs in jsdom for anything that touches window/document
 * (MetricChart's ResizeObserver mock, Sidebar's document.title write).
 *
 * The main vite.config.ts stays lean; this file is loaded only for
 * `npm run test:unit`.
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.{test,spec}.ts'],
    setupFiles: ['./src/test-setup.ts'],
    globals: false,
    // Keep the run fast — the whole point of the bootstrap is a
    // sub-2s smoke check on every FE commit.
    reporters: ['basic'],
  },
});
