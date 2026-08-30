import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { createRouter, createMemoryHistory } from 'vue-router';

import OnboardingAdmin from './OnboardingAdmin.vue';
import { OnboardingApi } from '@/api/onboarding';
import { useOnboardingStore } from '@/stores/onboarding';

/**
 * The recovery code is shown exactly once, at the moment the account is
 * created. There is no endpoint that will hand it back — that is the point —
 * so if this view navigates away, or lets another branch of the template win,
 * the operator has silently lost their only way back into the box.
 *
 * That is not hypothetical: the first cut ordered the "an admin already
 * exists" branch first, and since creating the account is precisely what
 * makes that true, the code never rendered at all.
 */
async function mountAdmin() {
  const pinia = createPinia();
  setActivePinia(pinia);
  const store = useOnboardingStore();
  store.hydrated = true;
  store.draft = {
    complete: false,
    bootstrap_mode: true,
    step: 'admin',
    admin_username: null,
    domain: 'aurora.local',
    enabled_packages: [],
    dns_mode: null,
  };

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/onboarding/admin', component: OnboardingAdmin },
      { path: '/onboarding/domain', component: { template: '<div>domain</div>' } },
    ],
  });
  await router.push('/onboarding/admin');
  await router.isReady();

  const w = mount(OnboardingAdmin, { global: { plugins: [pinia, router] } });
  await flushPromises();
  return { w, store, router };
}

beforeEach(() => {
  vi.spyOn(OnboardingApi, 'setAdmin').mockResolvedValue({ recoveryCode: 'amber-brook-cedar-dawn-ember-fable' });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('OnboardingAdmin', () => {
  it('shows the one-time recovery code after creating the account, and will not move on until it is acknowledged', async () => {
    const { w, store, router } = await mountAdmin();
    vi.spyOn(store, 'hydrate').mockImplementation(async () => {
      // What the real hydrate does after POST /onboarding/admin.
      store.draft = { ...store.draft!, bootstrap_mode: false, admin_username: 'sarah' };
      return store.draft;
    });

    await w.get('#uname').setValue('sarah');
    // The kit's Checkbox is a button[role=checkbox], not an <input>.
    await w.get('[role="checkbox"]').trigger('click');
    const continueBtn = w.findAll('button').find((b) => b.text().trim() === 'Continue');
    await continueBtn!.trigger('click');
    await flushPromises();

    const code = w.get('[data-test="recovery-code"]');
    expect(code.text()).toBe('amber-brook-cedar-dawn-ember-fable');
    // Still on the admin step: the code has not been acknowledged.
    expect(router.currentRoute.value.path).toBe('/onboarding/admin');

    await w.get('[data-test="recovery-continue"]').trigger('click');
    await flushPromises();
    expect(router.currentRoute.value.path).toBe('/onboarding/admin');

    // Only one checkbox is on screen in this branch: the recovery one.
    await w.get('[role="checkbox"]').trigger('click');
    await flushPromises();
    await w.get('[data-test="recovery-continue"]').trigger('click');
    await flushPromises();
    expect(router.currentRoute.value.path).toBe('/onboarding/domain');
  });
});
