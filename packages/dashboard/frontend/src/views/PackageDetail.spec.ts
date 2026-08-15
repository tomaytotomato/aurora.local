import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { createRouter, createMemoryHistory } from 'vue-router';
import { type AxiosAdapter, type AxiosResponse } from 'axios';
import PackageDetail from './PackageDetail.vue';
import { http } from '@/api/client';
import { JobsApi } from '@/api/jobs';
import type { PackageDetail as PackageDetailWire } from '@/api/packages';

/**
 * Stands in for the browser's real EventSource, which jsdom doesn't
 * provide. Only needed by tests that click a lifecycle action button —
 * that mounts JobLogPanel, which opens a stream immediately.
 */
class FakeEventSource {
  addEventListener(): void {}
  close(): void {}
}

/**
 * Full-mount tests for the app detail page's control panel
 * (Install/Start/Disable/Uninstall + status light) and the two bugs
 * fixed alongside it:
 *
 * - the action panel used to render nothing at all until the first GET
 *   /packages/{name} resolved, with no loading state to say why;
 * - navigating from one app's detail page to another's (same route,
 *   :name param change) never re-fetched — Vue Router reuses the
 *   component instance, so onMounted alone only ever runs once.
 *
 * Reuses the custom-adapter pattern from UsersView.spec.ts rather than
 * MSW, so each test controls exactly what the wire returns.
 */

interface Reply {
  url?: string | RegExp;
  method: string;
  status?: number;
  data?: unknown;
}

const responses: Reply[] = [];
const captured: { url?: string; method?: string }[] = [];

function installAdapter(): void {
  const adapter: AxiosAdapter = (config) => {
    captured.push({ url: config.url, method: config.method });
    const url = config.url ?? '';
    const method = (config.method ?? '').toLowerCase();
    for (const r of responses) {
      const matches =
        method === r.method.toLowerCase() &&
        (typeof r.url === 'string'
          ? url === r.url
          : r.url instanceof RegExp
            ? r.url.test(url)
            : true);
      if (matches) {
        return Promise.resolve({
          data: r.data ?? {},
          status: r.status ?? 200,
          statusText: 'OK',
          headers: {},
          config,
        } as AxiosResponse);
      }
    }
    // Fallback: 200 empty body. Keeps unrelated fetches (resources,
    // network, security findings…) from throwing and drowning the test
    // in unrelated failures.
    return Promise.resolve({ data: {}, status: 200, statusText: 'OK', headers: {}, config } as AxiosResponse);
  };
  http.defaults.adapter = adapter;
}

function stubResponse(reply: Reply): void {
  responses.push(reply);
}

function packageDetail(overrides: Partial<PackageDetailWire> & { name: string }): PackageDetailWire {
  return {
    title: overrides.name,
    category: 'productivity',
    description: 'A test package.',
    enabled: true,
    running: true,
    ...overrides,
  } as PackageDetailWire;
}

async function mountDetail(routeName: string) {
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/apps/:name', component: PackageDetail }],
  });
  await router.push(`/apps/${routeName}`);
  await router.isReady();
  const w = mount(PackageDetail, { global: { plugins: [pinia, router] } });
  await flushPromises();
  return { w, router };
}

beforeEach(() => {
  responses.length = 0;
  captured.length = 0;
  installAdapter();
  stubResponse({ method: 'get', url: '/updates', data: [] });
  stubResponse({
    method: 'get',
    url: '/services/status',
    data: { generated_at: new Date().toISOString(), services: [] },
  });
});

afterEach(() => {
  delete (http.defaults as { adapter?: AxiosAdapter }).adapter;
  vi.restoreAllMocks();
});

describe('PackageDetail control panel', () => {
  it('shows a loading skeleton before the detail resolves, not an empty gap', async () => {
    stubResponse({ method: 'get', url: '/packages/media', data: packageDetail({ name: 'media' }) });
    const pinia = createPinia();
    setActivePinia(pinia);
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/apps/:name', component: PackageDetail }],
    });
    await router.push('/apps/media');
    await router.isReady();
    const w = mount(PackageDetail, { global: { plugins: [pinia, router] } });

    // Before the first flush, the fetch is still in flight.
    expect(w.find('[data-test="package-actions-skeleton"]').exists()).toBe(true);
    expect(w.find('[data-card="package-actions"]').exists()).toBe(false);

    await flushPromises();
    expect(w.find('[data-test="package-actions-skeleton"]').exists()).toBe(false);
    expect(w.find('[data-card="package-actions"]').exists()).toBe(true);
  });

  it('a not-installed app offers only Install', async () => {
    stubResponse({
      method: 'get',
      url: '/packages/photos',
      data: packageDetail({ name: 'photos', enabled: false, running: false }),
    });
    const { w } = await mountDetail('photos');
    expect(w.find('[data-test="action-install"]').exists()).toBe(true);
    expect(w.find('[data-test="action-start"]').exists()).toBe(false);
    expect(w.find('[data-test="action-disable"]').exists()).toBe(false);
    expect(w.find('[data-test="action-uninstall"]').exists()).toBe(false);
  });

  it('a stopped-but-installed app offers Start and Uninstall but not Install', async () => {
    stubResponse({
      method: 'get',
      url: '/packages/photos',
      data: packageDetail({ name: 'photos', enabled: true, running: false }),
    });
    const { w } = await mountDetail('photos');
    expect(w.find('[data-test="action-install"]').exists()).toBe(false);
    expect(w.find('[data-test="action-start"]').exists()).toBe(true);
    expect(w.find('[data-test="action-uninstall"]').exists()).toBe(true);
  });

  it('a running app offers a clickable Disable and Uninstall, but not Start or Install', async () => {
    stubResponse({
      method: 'get',
      url: '/packages/photos',
      data: packageDetail({ name: 'photos', enabled: true, running: true }),
    });
    const { w } = await mountDetail('photos');
    expect(w.find('[data-test="action-install"]').exists()).toBe(false);
    expect(w.find('[data-test="action-start"]').exists()).toBe(false);
    const disableBtn = w.find('[data-test="action-disable"]');
    expect(disableBtn.exists()).toBe(true);
    expect(disableBtn.attributes('disabled')).toBeUndefined();
    expect(w.find('[data-test="action-uninstall"]').exists()).toBe(true);
  });

  it('clicking Disable posts to /packages/{name}/stop, not /disable', async () => {
    stubResponse({
      method: 'get',
      url: '/packages/photos',
      data: packageDetail({ name: 'photos', enabled: true, running: true }),
    });
    stubResponse({ method: 'post', url: '/packages/photos/stop', data: { jobId: 'job-stop-1' } });
    vi.spyOn(JobsApi, 'openStream').mockImplementation(() => new FakeEventSource() as unknown as EventSource);
    const { w } = await mountDetail('photos');

    await w.find('[data-test="action-disable"]').trigger('click');
    await flushPromises();

    expect(captured.some((c) => c.method === 'post' && c.url === '/packages/photos/stop')).toBe(true);
  });

  it('a core package offers none of the four actions, however its enabled/running flags read', async () => {
    stubResponse({
      method: 'get',
      url: '/packages/identity',
      data: packageDetail({ name: 'identity', enabled: true, running: true }),
    });
    const { w } = await mountDetail('identity');
    expect(w.find('[data-test="action-install"]').exists()).toBe(false);
    expect(w.find('[data-test="action-start"]').exists()).toBe(false);
    expect(w.find('[data-test="action-disable"]').exists()).toBe(false);
    expect(w.find('[data-test="action-uninstall"]').exists()).toBe(false);
  });

  it('the status light reads running for a genuinely healthy core package (the real-box bug)', async () => {
    stubResponse({
      method: 'get',
      url: '/packages/identity',
      data: packageDetail({ name: 'identity', enabled: true, running: true }),
    });
    const { w } = await mountDetail('identity');
    expect(w.find('[data-status-light="running"]').exists()).toBe(true);
    expect(w.find('[data-status-light="not-installed"]').exists()).toBe(false);
  });

  it('re-fetches package detail when the route :name param changes without a remount', async () => {
    // Vue Router reuses the PackageDetail instance across a /apps/media
    // -> /apps/photos navigation (same matched route, only the param
    // changes) — onMounted alone would never see the second package.
    stubResponse({ method: 'get', url: '/packages/media', data: packageDetail({ name: 'media', enabled: true, running: true }) });
    stubResponse({ method: 'get', url: '/packages/photos', data: packageDetail({ name: 'photos', enabled: false, running: false }) });

    const { w, router } = await mountDetail('media');
    expect(w.find('[data-test="action-install"]').exists()).toBe(false);
    expect(w.find('[data-test="action-uninstall"]').exists()).toBe(true);

    await router.push('/apps/photos');
    await flushPromises();

    expect(w.find('[data-test="action-install"]').exists()).toBe(true);
    expect(w.find('[data-test="action-uninstall"]').exists()).toBe(false);
    expect(captured.some((c) => c.method === 'get' && c.url === '/packages/photos')).toBe(true);
  });
});
