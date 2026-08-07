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

  it('reports partial (not "not-started") when some but not all are up', () => {
    // Regression guard: 4-of-5-up used to read "Not started" in the
    // TopBar on every page — the most-seen state, dishonest in its most
    // common partial case.
    const store = usePackagesStore();
    store.list = [pkg({ name: 'a', running: true }), pkg({ name: 'b', running: false })];
    const { pill } = useHealthPill();
    expect(pill.value.state).toBe('partial');
    expect(pill.value.text).toBe('Partly running');
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
});
