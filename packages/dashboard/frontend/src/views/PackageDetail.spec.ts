import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { createRouter, createMemoryHistory } from 'vue-router';
import { AxiosError, type AxiosAdapter, type AxiosResponse } from 'axios';
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
        const status = r.status ?? 200;
        const response = {
          data: r.data ?? {},
          status,
          statusText: 'OK',
          headers: {},
          config,
        } as AxiosResponse;
        // A stubbed non-2xx must actually reject, same as real axios.
        if (status < 200 || status >= 300) {
          const err = new AxiosError('stubbed failure', String(status), config, undefined, response);
          return Promise.reject(err);
        }
        return Promise.resolve(response);
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

/**
 * A package the header/control panel show as enabled and running must
 * never have a tab imply the opposite.
 */
describe('PackageDetail — a running package is never reported as not-started by any tab', () => {
  async function clickTab(w: Awaited<ReturnType<typeof mountDetail>>['w'], label: string): Promise<void> {
    const tabs = w.findAll('[role="tab"]');
    const tab = tabs.find((t) => t.text() === label);
    if (!tab) throw new Error(`no tab labelled "${label}"`);
    await tab.trigger('click');
    await flushPromises();
  }

  function runningNotesDetail() {
    return packageDetail({
      name: 'notes',
      title: 'Notes (SilverBullet)',
      enabled: true,
      running: true,
    });
  }

  it('the Logs tab shows the container list, not "nothing running yet", once containers resolve', async () => {
    stubResponse({ method: 'get', url: '/packages/notes', data: runningNotesDetail() });
    stubResponse({
      method: 'get',
      url: '/containers',
      data: [
        { id: 'abc', names: ['/silverbullet'], image: 'ghcr.io/silverbulletmd/silverbullet:latest',
          state: 'running', status: 'Up 2 seconds (health: starting)', service: 'silverbullet', labels: {} },
      ],
    });
    const { w } = await mountDetail('notes');

    await clickTab(w, 'Logs');

    expect(w.find('[data-test="package-logs-empty"]').exists()).toBe(false);
    expect(w.find('[data-test="package-logs-list"]').exists()).toBe(true);
    expect(w.text()).not.toContain('Nothing running for this app yet');
  });

  it('the Network tab answers instead of 404ing, and never claims the networking is gone', async () => {
    stubResponse({ method: 'get', url: '/packages/notes', data: runningNotesDetail() });
    stubResponse({
      method: 'get',
      url: '/packages/notes/network',
      data: {
        package: 'notes',
        mode: 'direct',
        gateway: null,
        locked: true,
        lockedReason: "Aurora doesn't support changing this from the dashboard yet.",
        containers: ['silverbullet'],
        publishedPorts: [3030],
        egressIp: null,
        egressCountry: null,
        gatewayHealthy: true,
      },
    });
    const { w } = await mountDetail('notes');

    await clickTab(w, 'Network');

    expect(w.find('[data-state="error"]').exists()).toBe(false);
    expect(w.find('[data-test="package-network-card"]').exists()).toBe(true);
    expect(w.text()).not.toContain('is not on this box any more');
  });

  it('a genuine 404 on the network endpoint reads as "can\'t find", not the old broken "That this app\'s..." sentence', async () => {
    stubResponse({ method: 'get', url: '/packages/notes', data: runningNotesDetail() });
    stubResponse({ method: 'get', url: '/packages/notes/network', status: 404, data: {} });
    const { w } = await mountDetail('notes');

    await clickTab(w, 'Network');

    expect(w.text()).toContain("Aurora can't find this app's networking on this box any more");
    expect(w.text()).not.toMatch(/\bThat this app's/);
  });
});

describe('PackageDetail — Overview ABOUT card', () => {
  it('falls back to the manifest description when readme is absent, instead of "No description yet"', async () => {
    stubResponse({
      method: 'get',
      url: '/packages/notes',
      data: packageDetail({
        name: 'notes',
        description: 'SilverBullet is a markdown-native notes system.',
      }),
    });
    const { w } = await mountDetail('notes');

    expect(w.text()).toContain('SilverBullet is a markdown-native notes system.');
    expect(w.text()).not.toContain('No description yet.');
  });

  it('still shows "No description yet" when the manifest genuinely has none', async () => {
    stubResponse({
      method: 'get',
      url: '/packages/notes',
      data: packageDetail({ name: 'notes', description: '' }),
    });
    const { w } = await mountDetail('notes');

    expect(w.text()).toContain('No description yet.');
  });
});
