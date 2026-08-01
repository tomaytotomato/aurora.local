import { execSync } from 'node:child_process';
import path from 'node:path';

/**
 * Global setup: reset the isolated aurora-e2e project so every full test
 * run starts from a fresh box (blank .state.yml, no admin created).
 *
 * Skipped when AURORA_E2E_SKIP_RESET=1 (useful when iterating on a single
 * spec against an already-running instance).
 */
export default async function globalSetup() {
  if (process.env.AURORA_E2E_SKIP_RESET === '1') {
    console.log('[e2e] AURORA_E2E_SKIP_RESET=1 → skipping reset-aurora-e2e.sh');
    return;
  }
  const script = path.resolve(__dirname, 'scripts/reset-aurora-e2e.sh');
  console.log(`[e2e] running ${script}`);
  execSync(`bash ${script}`, { stdio: 'inherit' });
}
