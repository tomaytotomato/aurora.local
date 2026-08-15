import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';

import DoneChecklist from './DoneChecklist.vue';
import { ServicesApi, type ServiceStatus, type ServicesStatusResponse } from '@/api/services';
import { PackagesApi } from '@/api/packages';

/**
 * Regression coverage for the Done-page false negative: the dashboard
 * reported itself as "Not started" — with a "Start" button — on the very
 * screen it was serving, because packages/dashboard/manifest.yml had no
 * probe: block, so the backend fell back to looking for a container
 * literally named "dashboard" (the real one is "aurora"). The backend fix
 * (packages/dashboard/manifest.yml's probe.kind: self) is covered in
 * StatusProbeServiceTests/PackagesServiceTests; these tests pin the same
 * thing at the component boundary DoneChecklist actually renders from —
 * whatever GET /api/services/status returns — independent of the
 * `enabledPackages` prop, which the wizard never populates with
 * "dashboard" (see stores/onboarding.ts) and must not need to.
 */

function statusOf(services: ServiceStatus[]): ServicesStatusResponse {
  return { generated_at: new Date().toISOString(), services };
}

function serviceRow(overrides: Partial<ServiceStatus> = {}): ServiceStatus {
  return {
    package: 'dashboard',
    container: 'aurora',
    state: 'running',
    reason: null,
    detail: null,
    open_url: 'http://aurora.local/',
    priority: 4,
    probed_ms: 3,
    ...overrides,
  };
}

function mountChecklist(enabledPackages: string[] = []) {
  return mount(DoneChecklist, { props: { enabledPackages } });
}

beforeEach(() => {
  setActivePinia(createPinia());
  vi.spyOn(PackagesApi, 'list').mockResolvedValue([]);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('DoneChecklist', () => {
  it('reports a running dashboard as running, never as not-started', async () => {
    vi.spyOn(ServicesApi, 'status').mockResolvedValue(statusOf([serviceRow()]));

    const w = mountChecklist();
    await flushPromises();

    const row = w.get('[data-package="dashboard"]');
    expect(row.attributes('data-tone')).toBe('ok');
    expect(row.find('[data-status="running"]').exists()).toBe(true);
    expect(row.find('[data-status="not-started"]').exists()).toBe(false);
    expect(row.text()).not.toContain('Not started');

    w.unmount();
  });

  it('offers Open, not Start, for a package that is already running', async () => {
    vi.spyOn(ServicesApi, 'status').mockResolvedValue(statusOf([serviceRow()]));

    const w = mountChecklist();
    await flushPromises();

    const row = w.get('[data-package="dashboard"]');
    expect(row.text()).toContain('Open');
    // The 'not-started' CTA path renders a data-test="row-cta" button
    // labelled Start; a running row must never offer that affordance.
    expect(row.find('[data-test="row-cta"]').exists()).toBe(false);

    w.unmount();
  });

  it('shows the dashboard\'s live state even though the wizard never selects it', async () => {
    // toStart / packages_to_start never includes "dashboard" (see
    // stores/onboarding.ts and PackagesService.INFRASTRUCTURE_PACKAGES) —
    // DoneChecklist must source the dashboard's row from the real backend
    // snapshot, not from the enabledPackages the launch CTA cares about.
    vi.spyOn(ServicesApi, 'status').mockResolvedValue(
      statusOf([serviceRow(), serviceRow({ package: 'core', priority: 4 })]),
    );

    const w = mountChecklist(['core']); // toStart-shaped prop: never 'dashboard'

    await flushPromises();

    expect(w.find('[data-package="dashboard"]').exists()).toBe(true);
    expect(w.get('[data-package="dashboard"]').find('[data-status="running"]').exists()).toBe(true);

    w.unmount();
  });

  it('still offers Start for a package genuinely not started', async () => {
    // Sanity check the other side of the fix: a real not-started package
    // (unrelated to the dashboard) must keep the Start CTA, so the fix
    // above didn't just paper over every row.
    vi.spyOn(ServicesApi, 'status').mockResolvedValue(statusOf([
      {
        package: 'media',
        container: null,
        state: 'not-started',
        reason: 'Not started yet',
        detail: "Aurora hasn't brought this online yet.",
        open_url: null,
        priority: 2,
        probed_ms: 4,
      },
    ]));

    const w = mountChecklist();
    await flushPromises();

    const row = w.get('[data-package="media"]');
    expect(row.find('[data-status="not-started"]').exists()).toBe(true);
    expect(row.text()).toContain('Start');

    w.unmount();
  });
});
