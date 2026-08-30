import { setActivePinia, createPinia } from 'pinia';
import { beforeEach, describe, expect, it } from 'vitest';
import { usePackagesStore } from '@/stores/packages';
import { useHealthPill } from './useHealthPill';
import type { PackageSummary } from '@/api/packages';

function pkg(over: Partial<PackageSummary> & { name: string }): PackageSummary {
  return {
    category: 'core',
    description: '',
    enabled: true,
    running: false,
    ...over,
  };
}

describe('useHealthPill', () => {
  beforeEach(() => setActivePinia(createPinia()));

  it('reports running when every enabled package is up', () => {
    const store = usePackagesStore();
    store.list = [pkg({ name: 'a', running: true }), pkg({ name: 'b', running: true })];
    const { pill } = useHealthPill();
    expect(pill.value.state).toBe('running');
    expect(pill.value.tone).toBe('ok');
  });

  it('scopes the pill copy to apps so it never reads as a global verdict', () => {
    // The pill is derived only from package running-state. A bare "All
    // good" implied a box-wide all-clear and could sit green while a
    // security finding was open. Every state string must name its scope.
    const store = usePackagesStore();
    store.list = [pkg({ name: 'a', running: true })];
    const { pill } = useHealthPill();
    expect(pill.value.text).toBe('Apps: all running');
    expect(pill.value.text.toLowerCase()).not.toBe('all good');
  });

  it('reports partial (not "not-started") when some but not all are up', () => {
    // Regression guard: 4-of-5-up used to read "Not started" in the
    // TopBar on every page — the most-seen state, dishonest in its most
    // common partial case.
    const store = usePackagesStore();
    store.list = [pkg({ name: 'a', running: true }), pkg({ name: 'b', running: false })];
    const { pill } = useHealthPill();
    expect(pill.value.state).toBe('partial');
    expect(pill.value.text).toBe('Apps: partly running');
  });

  it('reports not-started when enabled packages exist but none are up', () => {
    const store = usePackagesStore();
    store.list = [pkg({ name: 'a', running: false })];
    const { pill } = useHealthPill();
    expect(pill.value.state).toBe('not-started');
  });

  it('reports not-started when nothing is enabled', () => {
    const store = usePackagesStore();
    store.list = [pkg({ name: 'a', enabled: false, running: false })];
    const { pill } = useHealthPill();
    expect(pill.value.state).toBe('not-started');
  });

  it('reports partial when a package is degraded even if every enabled package is running', () => {
    // Review 2026-08-30 item 2 regression guard. `core` shows running
    // because caddy/db/stalwart hold it up while Authelia inside it
    // restart-loops. Before this branch the pill went green there and
    // every gated vhost 502'd underneath it.
    const store = usePackagesStore();
    store.list = [pkg({ name: 'core', running: true, degraded: true })];
    const { pill } = useHealthPill();
    expect(pill.value.state).toBe('partial');
    expect(pill.value.tone).toBe('warn');
    expect(pill.value.text).toBe('Apps: partly running');
  });

  it('treats absent degraded as false so pre-fix wire payloads still read running', () => {
    // Older backend releases don't emit `degraded` at all. Absent must
    // be neutral, not a truthy "undefined".
    const store = usePackagesStore();
    store.list = [pkg({ name: 'a', running: true })];
    const { pill } = useHealthPill();
    expect(pill.value.state).toBe('running');
  });
});
