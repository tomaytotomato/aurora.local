import { describe, expect, it } from 'vitest';
import { opensThisDashboard, type ServiceStatus } from './services';

function svc(over: Partial<ServiceStatus> & { package: string }): ServiceStatus {
  return {
    container: null,
    state: 'running',
    reason: null,
    detail: null,
    open_url: null,
    priority: 0,
    probed_ms: 0,
    ...over,
  };
}

/**
 * The "Bring your box online" checklist offers an Open button per row.
 * The `core` row (Caddy + the apex domain) opens the dashboard you are
 * already on, so it must not offer one — a button that reloads the
 * current page is noise. Everything else opens a genuinely different app.
 */
describe('opensThisDashboard', () => {
  it('is true for the core row (the apex proxy is the dashboard itself)', () => {
    expect(opensThisDashboard(svc({ package: 'core' }))).toBe(true);
  });

  it('is false for every other app', () => {
    for (const name of ['media', 'photos', 'notes', 'privacy', 'storage']) {
      expect(opensThisDashboard(svc({ package: name }))).toBe(false);
    }
  });
});
