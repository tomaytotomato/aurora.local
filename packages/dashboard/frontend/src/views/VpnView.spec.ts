import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { AxiosError, type AxiosAdapter, type AxiosResponse } from 'axios';
import VpnView from './VpnView.vue';
import { http } from '@/api/client';

/**
 * The VPN page's configured / not-configured states, and the one-way door
 * between them.
 *
 * Found on the testbed by clicking: land on /vpn with no VPN, get the
 * "Generate your server configuration" card — the only control on it —
 * and afterwards the page reads as configured for good. There was no
 * DELETE for the server config in the backend or in openapi.yaml, so the
 * setup screen could never come back; a keypair had been written, the
 * endpoint was empty, wg0 did not exist and there were no peers, and the
 * page still showed three tabs of a working VPN.
 *
 * Adapter-stub pattern borrowed from PackageDetail.spec.ts so each test
 * controls exactly what the wire returns.
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
        (typeof r.url === 'string' ? url === r.url : r.url instanceof RegExp ? r.url.test(url) : true);
      if (matches) {
        const status = r.status ?? 200;
        const response = { data: r.data ?? {}, status, statusText: 'OK', headers: {}, config } as AxiosResponse;
        if (status < 200 || status >= 300) {
          return Promise.reject(new AxiosError('stubbed failure', String(status), config, undefined, response));
        }
        return Promise.resolve(response);
      }
    }
    return Promise.resolve({ data: {}, status: 200, statusText: 'OK', headers: {}, config } as AxiosResponse);
  };
  http.defaults.adapter = adapter;
}

function stubResponse(reply: Reply): void {
  responses.unshift(reply);
}

const CONFIGURED = {
  endpointHost: 'aurora.duckdns.org',
  listenPort: 51820,
  dns: '1.1.1.1',
  serverAddress: '10.66.66.1/24',
  mtu: 1420,
  serverPublicKey: 'pubkey=',
};

/** What POST /vpn/config/init actually returns on a real box: keys, nothing else. */
const JUST_GENERATED = { ...CONFIGURED, endpointHost: '', serverPublicKey: 'freshpub=' };

function status(overrides: Record<string, unknown> = {}) {
  return {
    runState: 'running',
    interface: 'wg0',
    listenPort: 51820,
    endpoint: 'aurora.duckdns.org:51820',
    peersTotal: 0,
    peersOnline: 0,
    reachable: null,
    lastCheckedAt: '2026-08-18T10:00:00Z',
    generatedAt: '2026-08-18T10:00:00Z',
    ...overrides,
  };
}

async function mountVpn() {
  const pinia = createPinia();
  setActivePinia(pinia);
  const w = mount(VpnView, { global: { plugins: [pinia] } });
  await flushPromises();
  return w;
}

/**
 * Dialog teleports to <body>, so its buttons are not inside the mounted
 * wrapper. Reach for them through the document, and clear it between
 * tests so a previous dialog cannot satisfy the next assertion.
 */
function inDialog(selector: string): HTMLElement | null {
  return document.body.querySelector<HTMLElement>(selector);
}

beforeEach(() => {
  responses.length = 0;
  captured.length = 0;
  document.body.innerHTML = '';
  installAdapter();
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  delete (http.defaults as { adapter?: AxiosAdapter }).adapter;
  vi.restoreAllMocks();
});

describe('with no VPN configured', () => {
  beforeEach(() => {
    stubResponse({ method: 'get', url: '/vpn/status', data: status({ runState: 'not-configured' }) });
    stubResponse({ method: 'get', url: '/vpn/config', status: 404 });
  });

  it('offers to generate one', async () => {
    const w = await mountVpn();
    expect(w.find('[data-test="vpn-not-configured"]').exists()).toBe(true);
  });

  it('does not generate anything just by being looked at', async () => {
    // The whole reason a config appeared on the testbed without anyone
    // meaning to create one. Visiting the page must never write.
    await mountVpn();
    const writes = captured.filter((c) => (c.method ?? '').toLowerCase() !== 'get');
    expect(writes).toEqual([]);
  });
});

describe('removing the configuration', () => {
  beforeEach(() => {
    stubResponse({ method: 'get', url: '/vpn/status', data: status() });
    stubResponse({ method: 'get', url: '/vpn/config', data: CONFIGURED });
    stubResponse({ method: 'get', url: '/vpn/peers', data: [] });
  });

  it('is offered beside regenerating the key, in the Server card', async () => {
    // Both destructive server-level actions sit under the same rule in
    // the Overview tab's Server card. The Advanced tab is OpenVPN.
    const w = await mountVpn();
    expect(w.find('[data-test="vpn-remove-open"]').exists()).toBe(true);
    expect(w.find('[data-test="vpn-rotate-open"]').exists()).toBe(true);
  });

  it('asks first, because every peer goes with it', async () => {
    const w = await mountVpn();
    await w.find('[data-test="vpn-remove-open"]').trigger('click');
    await flushPromises();

    // Opening the confirm must not itself delete anything.
    expect(captured.some((c) => (c.method ?? '').toLowerCase() === 'delete')).toBe(false);
    expect(inDialog('[data-test="vpn-remove-confirm"]')).not.toBeNull();
  });

  it('returns the page to the setup screen once confirmed', async () => {
    stubResponse({ method: 'delete', url: '/vpn/config', status: 204 });
    const w = await mountVpn();
    await w.find('[data-test="vpn-remove-open"]').trigger('click');
    await flushPromises();
    inDialog('[data-test="vpn-remove-confirm"]')!.click();
    await flushPromises();

    expect(captured.some((c) => c.url === '/vpn/config' && (c.method ?? '').toLowerCase() === 'delete')).toBe(true);
    // The point of the whole exercise: the door swings both ways without
    // a page reload.
    expect(w.find('[data-test="vpn-not-configured"]').exists()).toBe(true);
  });
});

describe('a configuration that exists but cannot work', () => {
  it('says what is missing rather than reporting a working VPN', async () => {
    // Exactly the state a single click on Generate leaves behind: a
    // keypair, no endpoint, no peers, and no wg0 on the box. Reporting
    // that as set up is the same dishonesty as a box that believes it is
    // backed up.
    stubResponse({ method: 'get', url: '/vpn/status', data: status({ runState: 'stopped', interface: null, endpoint: null }) });
    stubResponse({ method: 'get', url: '/vpn/config', data: JUST_GENERATED });
    stubResponse({ method: 'get', url: '/vpn/peers', data: [] });

    const w = await mountVpn();
    const notice = w.find('[data-test="vpn-incomplete"]');
    expect(notice.exists()).toBe(true);
    expect(notice.text()).toMatch(/endpoint/i);
  });

  it('stays quiet once the configuration is actually usable', async () => {
    stubResponse({ method: 'get', url: '/vpn/status', data: status() });
    stubResponse({ method: 'get', url: '/vpn/config', data: CONFIGURED });
    stubResponse({
      method: 'get',
      url: '/vpn/peers',
      data: [{
        id: 'peer-1', name: 'phone', publicKey: 'p=', allowedIps: '10.66.66.2/32',
        killSwitch: false, enabled: true, lastHandshakeAt: null, rxBytes: 0, txBytes: 0,
        createdAt: '2026-08-18T10:00:00Z',
      }],
    });

    const w = await mountVpn();
    expect(w.find('[data-test="vpn-incomplete"]').exists()).toBe(false);
  });
});
