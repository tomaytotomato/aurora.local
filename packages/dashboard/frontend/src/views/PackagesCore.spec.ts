import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { createRouter, createMemoryHistory } from 'vue-router';

import PackagesCore from './PackagesCore.vue';
import { ContainersApi, type ContainerInfo } from '@/api/containers';
import { CORE_SERVICES } from '@/api/core-services';

/**
 * Regression coverage for the "Core page only shows Caddy" bug.
 *
 * The previous version rendered one card per package, and `core` is a
 * single package that runs three containers plus lives alongside a
 * fourth. The Caddy icon on that single card read as "only Caddy runs
 * here", which was wrong and misleading.
 *
 * This suite pins the new shape: one card per service in CORE_SERVICES,
 * running-state driven off /api/containers, and Aurora specifically
 * rendered as a non-clickable card with a "go to Settings" hint.
 */

function fakeContainer(name: string, state: string = 'running'): ContainerInfo {
  return {
    id: `id-${name}`,
    names: [`/${name}`],
    image: `example/${name}:latest`,
    state,
    status: 'Up 2 hours (healthy)',
    service: name,
    labels: { 'com.docker.compose.project': 'aurora' },
  };
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/apps/catalogue', component: { template: '<div />' } },
      { path: '/apps/core', component: { template: '<div />' } },
      { path: '/apps/core/services/:service', component: { template: '<div />' } },
      { path: '/settings', component: { template: '<div />' } },
    ],
  });
}

async function mountCore() {
  const router = makeRouter();
  await router.push('/apps/core');
  await router.isReady();
  const wrapper = mount(PackagesCore, {
    global: { plugins: [createPinia(), router] },
  });
  await flushPromises();
  return wrapper;
}

describe('PackagesCore', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('renders one card per core service', async () => {
    vi.spyOn(ContainersApi, 'list').mockResolvedValue(
      CORE_SERVICES.map((s) => fakeContainer(s.container)),
    );

    const wrapper = await mountCore();

    for (const svc of CORE_SERVICES) {
      expect(
        wrapper.find(`[data-test="core-service-${svc.key}"]`).exists(),
        `expected a card for ${svc.key}`,
      ).toBe(true);
    }
  });

  it("Aurora's card is non-clickable and points at Settings", async () => {
    // The whole reason the aurora entry is in this list is to *not*
    // pretend it is an app you configure via a details page. This test
    // pins both halves of that promise: no router-link, and a copy
    // string that says the operator should go to Settings.
    vi.spyOn(ContainersApi, 'list').mockResolvedValue([
      fakeContainer('caddy'),
      fakeContainer('aurora'),
      fakeContainer('authelia'),
      fakeContainer('stalwart'),
    ]);

    const wrapper = await mountCore();

    const aurora = wrapper.find('[data-test="core-service-aurora"]');
    expect(aurora.exists()).toBe(true);

    // The disabled card is a plain <div>, not a <router-link>; the
    // component wrapping router-link is `<a>` in the DOM.
    expect(aurora.element.tagName).toBe('DIV');

    const hint = wrapper.find('[data-test="core-service-hint"]');
    expect(hint.exists()).toBe(true);
    expect(hint.text()).toContain('Settings');
    expect(hint.text().toLowerCase()).toContain('dashboard');
  });

  it("linkable services deep-link into their details page", async () => {
    vi.spyOn(ContainersApi, 'list').mockResolvedValue([
      fakeContainer('caddy'),
      fakeContainer('authelia'),
      fakeContainer('stalwart'),
    ]);

    const wrapper = await mountCore();

    // Each non-disabled card is an <a> from vue-router with the
    // per-service href baked in.
    const caddy = wrapper.find('[data-test="core-service-caddy"]');
    expect(caddy.element.tagName).toBe('A');
    expect(caddy.attributes('href')).toBe('/apps/core/services/caddy');

    const authelia = wrapper.find('[data-test="core-service-authelia"]');
    expect(authelia.attributes('href')).toBe('/apps/core/services/authelia');
  });

  it("shows 'not running' for a core service with no matching container", async () => {
    // Stalwart is down. Caddy + Authelia are up. Aurora is always
    // rendered as "you are here" regardless of docker state because
    // detaching it from the compose project is a valid deployment.
    vi.spyOn(ContainersApi, 'list').mockResolvedValue([
      fakeContainer('caddy'),
      fakeContainer('authelia'),
    ]);

    const wrapper = await mountCore();

    const stalwart = wrapper.find('[data-test="core-service-stalwart"]');
    expect(stalwart.text()).toContain('not running');
  });

  it("renders a retry button when /api/containers fails and does not crash", async () => {
    vi.spyOn(ContainersApi, 'list').mockRejectedValue(new Error('boom'));
    const wrapper = await mountCore();
    expect(wrapper.find('[data-test="core-error"]').exists()).toBe(true);
  });
});
