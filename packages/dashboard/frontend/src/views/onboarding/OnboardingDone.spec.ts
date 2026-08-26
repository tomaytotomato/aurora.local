import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { createRouter, createMemoryHistory } from 'vue-router';

import OnboardingDone from './OnboardingDone.vue';
import {
  OnboardingApi,
  type LaunchStart,
  type LaunchStatus,
  type OnboardingEnv,
} from '@/api/onboarding';
import { useOnboardingStore } from '@/stores/onboarding';

/**
 * Regression coverage for the onboarding launch/complete ordering bug:
 * OnboardingReview.vue used to call POST /onboarding/complete right after
 * install(), before the Done page ever called POST /onboarding/launch.
 * The backend's guardMidOnboarding() refuses any mutating onboarding call
 * once complete=true, so the launch always 409'd and nobody could finish
 * the wizard.
 *
 * The fix moves the commit here, to OnboardingDone.vue, and only after a
 * launch has actually succeeded (or there was nothing to launch in the
 * first place). These tests pin that ordering directly against the
 * component rather than just the API layer, since the bug was entirely in
 * *when* the frontend called things, not in what the backend accepts.
 */

class FakeEventSource {
  static last: FakeEventSource | null = null;
  listeners: Record<string, Array<(e: MessageEvent) => void>> = {};
  closed = false;

  constructor() {
    FakeEventSource.last = this;
  }

  addEventListener(type: string, fn: (e: MessageEvent) => void): void {
    (this.listeners[type] ||= []).push(fn);
  }

  close(): void {
    this.closed = true;
  }

  emit(type: string, data: string): void {
    for (const fn of this.listeners[type] ?? []) fn(new MessageEvent(type, { data }));
  }
}

const ENV: OnboardingEnv = {
  hostname: 'aurora',
  lanIp: '192.168.1.50',
  distro: null,
  kernel: null,
  dockerVersion: null,
};

let callOrder: string[];

function mockApi(): void {
  callOrder = [];
  vi.spyOn(OnboardingApi, 'env').mockResolvedValue(ENV);
  vi.spyOn(OnboardingApi, 'startLaunch').mockImplementation(async () => {
    callOrder.push('launch');
    return {
      job_id: 'job-1',
      packages: ['core'],
      started_at: new Date().toISOString(),
    } satisfies LaunchStart;
  });
  vi.spyOn(OnboardingApi, 'openLaunchStream').mockImplementation(
    () => new FakeEventSource() as unknown as EventSource,
  );
  vi.spyOn(OnboardingApi, 'complete').mockImplementation(async () => {
    callOrder.push('complete');
  });
  vi.spyOn(OnboardingApi, 'getLaunchStatus').mockResolvedValue({
    id: 'job-1',
    state: 'running',
    packages: ['core'],
    started_at: new Date().toISOString(),
    finished_at: null,
    exit_code: null,
    failure_reason: null,
    tail: [],
  } satisfies LaunchStatus);
}

async function mountDone(packagesToStart: string[]) {
  const pinia = createPinia();
  setActivePinia(pinia);
  const store = useOnboardingStore();
  store.draft = {
    complete: false,
    bootstrap_mode: false,
    step: 'done',
    admin_username: 'admin',
    domain: 'aurora.local',
    enabled_packages: packagesToStart,
    dns_mode: null,
  };
  store.installResult = {
    applied: [],
    packages_to_start: packagesToStart,
    packages_to_stop: [],
    host_command: 'cd ~/aurora.local && ./scripts/up.sh',
  };

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div>dashboard</div>' } },
      { path: '/onboarding/done', component: OnboardingDone },
    ],
  });
  await router.push('/onboarding/done');
  await router.isReady();

  const w = mount(OnboardingDone, {
    global: {
      plugins: [pinia, router],
      stubs: { DoneChecklist: true, ReachInfo: true },
    },
  });
  await flushPromises();
  return { w, store, router };
}

beforeEach(() => {
  mockApi();
  sessionStorage.clear();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('OnboardingDone', () => {
  it('commits onboarding only after the launch actually succeeds, not before', async () => {
    const { w, store } = await mountDone(['core']);

    // Nothing committed yet — the CTA to the dashboard must stay disabled.
    expect(OnboardingApi.complete).not.toHaveBeenCalled();
    expect(w.find('[data-testid="to-dashboard"]').attributes('disabled')).toBeDefined();

    await w.find('[data-testid="start-services"]').trigger('click');
    await flushPromises();

    // Launch has started; complete must not have fired yet.
    expect(callOrder).toEqual(['launch']);
    expect(w.find('[data-testid="to-dashboard"]').attributes('disabled')).toBeDefined();

    FakeEventSource.last!.emit('done', JSON.stringify({ state: 'success' }));
    await flushPromises();

    // Complete only fires after launch, never before.
    expect(callOrder).toEqual(['launch', 'complete']);
    expect(store.draft?.complete).toBe(true);
    expect(w.find('[data-testid="to-dashboard"]').attributes('disabled')).toBeUndefined();
  });

  it('never commits onboarding when the launch fails, so the user can retry', async () => {
    const { w, store } = await mountDone(['core']);

    await w.find('[data-testid="start-services"]').trigger('click');
    await flushPromises();

    FakeEventSource.last!.emit(
      'done',
      JSON.stringify({ state: 'failed', reason: 'up.sh exited 1' }),
    );
    await flushPromises();

    expect(OnboardingApi.complete).not.toHaveBeenCalled();
    expect(store.draft?.complete).toBe(false);
    expect(w.find('[data-testid="to-dashboard"]').attributes('disabled')).toBeDefined();
    // The retry affordance must be there — a failed launch is not a dead end.
    expect(w.find('[data-testid="launch-retry"]').exists()).toBe(true);
  });

  it('commits immediately when there is nothing left to start', async () => {
    const { store } = await mountDone([]);

    expect(OnboardingApi.startLaunch).not.toHaveBeenCalled();
    expect(OnboardingApi.complete).toHaveBeenCalledTimes(1);
    expect(store.draft?.complete).toBe(true);
  });

  it('rehydrates a stored job on a refresh and commits once it finds it already succeeded', async () => {
    sessionStorage.setItem(
      'aurora.launch.currentJob',
      JSON.stringify({ jobId: 'job-1', startedAt: Date.now() }),
    );
    vi.spyOn(OnboardingApi, 'getLaunchStatus').mockResolvedValue({
      id: 'job-1',
      state: 'success',
      packages: ['core'],
      started_at: new Date().toISOString(),
      finished_at: new Date().toISOString(),
      exit_code: 0,
      failure_reason: null,
      tail: [],
    });

    const { store } = await mountDone(['core']);

    expect(OnboardingApi.complete).toHaveBeenCalledTimes(1);
    expect(store.draft?.complete).toBe(true);
  });

  it('rehydrates a still-running stored job on refresh without committing early', async () => {
    sessionStorage.setItem(
      'aurora.launch.currentJob',
      JSON.stringify({ jobId: 'job-1', startedAt: Date.now() }),
    );

    const { w, store } = await mountDone(['core']);

    expect(OnboardingApi.complete).not.toHaveBeenCalled();
    expect(store.draft?.complete).toBe(false);
    expect(w.find('[data-testid="launch-progress"]').exists()).toBe(true);
  });
});
