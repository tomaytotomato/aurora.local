import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { createRouter, createMemoryHistory } from 'vue-router';

import OnboardingReview from './OnboardingReview.vue';
import { OnboardingApi, type InstallPlan, type InstallResult, type OnboardingDraft } from '@/api/onboarding';
import { useOnboardingStore } from '@/stores/onboarding';

/**
 * Regression coverage for the onboarding launch/complete ordering bug.
 * This view used to call
 * POST /onboarding/complete right after install(), which flips
 * onboarding.complete = true before OnboardingDone.vue ever gets a chance
 * to call POST /onboarding/launch — and the backend refuses to launch once
 * complete is true. The fix is that this view must never call /complete at
 * all; that call now lives in OnboardingDone.vue, after a successful launch.
 */

const PLAN: InstallPlan = {
  packagesToEnable: ['core'],
  packagesToDisable: [],
  vhosts: [],
  ports: [],
  warnings: [],
};

const INSTALL_RESULT: InstallResult = {
  applied: ['core is enabled.'],
  packages_to_start: ['core'],
  packages_to_stop: [],
  host_command: 'cd ~/aurora.local && ./scripts/up.sh',
};

const DRAFT: OnboardingDraft = {
  complete: false,
  bootstrap_mode: false,
  step: 'done',
  admin_username: 'admin',
  domain: 'aurora.local',
  enabled_packages: ['core'],
  dns_mode: null,
};

async function mountReview() {
  const pinia = createPinia();
  setActivePinia(pinia);
  const store = useOnboardingStore();
  store.hydrated = true; // skip the hydrate() network call
  store.draft = DRAFT;
  store.domain = 'aurora.local';

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/onboarding/review', component: OnboardingReview },
      { path: '/onboarding/sso', component: { template: '<div>sso</div>' } },
      { path: '/onboarding/done', component: { template: '<div>done</div>' } },
    ],
  });
  await router.push('/onboarding/review');
  await router.isReady();

  const w = mount(OnboardingReview, {
    global: { plugins: [pinia, router] },
  });
  await flushPromises();
  return { w, store, router };
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.spyOn(OnboardingApi, 'plan').mockResolvedValue(PLAN);
  vi.spyOn(OnboardingApi, 'patch').mockResolvedValue(DRAFT);
  vi.spyOn(OnboardingApi, 'install').mockResolvedValue(INSTALL_RESULT);
  vi.spyOn(OnboardingApi, 'complete').mockResolvedValue(undefined);
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('OnboardingReview', () => {
  it('installs and moves on to the SSO step, never skipping it, and never calls /complete', async () => {
    const { w, store, router } = await mountReview();

    await w.find('[data-cta="primary"]').trigger('click');
    // patchDraft() -> install() -> the 350ms UX pause before navigating.
    await flushPromises();
    await vi.advanceTimersByTimeAsync(350);
    await flushPromises();

    expect(OnboardingApi.install).toHaveBeenCalledTimes(1);
    expect(OnboardingApi.complete).not.toHaveBeenCalled();
    expect(store.installResult).toEqual(INSTALL_RESULT);
    // Not '/onboarding/done'. Jumping there skipped second-factor
    // enrolment, which is what left every gated app unopenable on a fresh
    // box — the reason the SSO step exists at all.
    expect(router.currentRoute.value.path).toBe('/onboarding/sso');
    expect([...store.completed]).toContain('review');
  });

  it('leaves onboarding on Review when install fails, and still never calls /complete', async () => {
    vi.spyOn(OnboardingApi, 'install').mockRejectedValue(
      Object.assign(new Error('boom'), { response: { data: { message: 'up.sh not found' } } }),
    );
    const { w, router } = await mountReview();

    await w.find('[data-cta="primary"]').trigger('click');
    await flushPromises();

    expect(OnboardingApi.complete).not.toHaveBeenCalled();
    expect(router.currentRoute.value.path).toBe('/onboarding/review');
    expect(w.find('[data-tone="err"]').exists()).toBe(true);
  });
});
