import { execSync } from 'node:child_process';
import path from 'node:path';

/**
 * Global teardown: bring the aurora-e2e compose project down so it does
 * not linger between runs. Live aurora on :8090 is a different project
 * name and is not touched.
 */
export default async function globalTeardown() {
  if (process.env.AURORA_E2E_KEEP === '1') {
    console.log('[e2e] AURORA_E2E_KEEP=1 → leaving aurora-e2e running');
    return;
  }
  const script = path.resolve(__dirname, 'scripts/teardown.sh');
  try {
    execSync(`bash ${script}`, { stdio: 'inherit' });
  } catch (e) {
    console.warn('[e2e] teardown failed (non-fatal):', (e as Error).message);
  }
}
