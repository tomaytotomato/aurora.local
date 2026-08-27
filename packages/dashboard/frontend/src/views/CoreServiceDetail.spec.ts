import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { createRouter, createMemoryHistory } from 'vue-router';

import CoreServiceDetail from './CoreServiceDetail.vue';
import { ContainersApi, type ContainerInfo } from '@/api/containers';
import { SsoApi, type SsoNotification } from '@/api/sso';
import { useSystemStore } from '@/stores/system';

/**
 * The Core-service detail view: shared shell for Caddy/Authelia/Stalwart,
 * with an Authelia-only Notifications panel.
 *
 * The Notifications panel is the reason this view exists: it surfaces
 * OTPs and enrollment links from Authelia's filesystem notifier so the
 * operator does not have to shell into a container. Coverage below pins
 * the important promises:
 *   - Non-Authelia services do not render the Notifications section.
 *   - The panel calls /api/sso/notifications with limit=5.
 *   - An OTP is rendered as a copyable code with a revoke link.
 *   - A link-only entry (enrollment) renders the URL, no OTP block.
 *   - The empty state explains what triggers a notification.
 */

function container(name: string): ContainerInfo {
  return {
    id: `id-${name}`,
    names: [`/${name}`],
    image: `example/${name}:latest`,
    state: 'running',
    status: 'Up 5 minutes (healthy)',
    service: name,
    labels: {},
  };
}

const OTP_NOTIFICATION: SsoNotification = {
  date: '2026-08-27 14:04:47.408006237 +0100 BST m=+8934.592675333',
  recipient: '{Bruce bruce@aurora.local}',
  subject: 'Confirm your identity',
  otp: 'PHV9ZVAV',
  urls: ['https://auth.aurora.local/revoke/one-time-code?id=abc'],
  body: 'Hi Bruce, ...',
};

const LINK_NOTIFICATION: SsoNotification = {
  date: '2026-08-28 09:12:03.001 +0100 BST m=+9200.000',
  recipient: '{Bruce bruce@aurora.local}',
  subject: 'Register your device',
  otp: null,
  urls: ['https://auth.aurora.local/webauthn/register?token=abc'],
  body: 'Click the link below ...',
};

function makeRouter(service: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/apps/catalogue', component: { template: '<div />' } },
      { path: '/apps/core', component: { template: '<div />' } },
      { path: '/settings', component: { template: '<div />' } },
      { path: '/apps/core/services/:service', component: { template: '<div />' } },
      {
        path: '/containers/:id/logs',
        component: { template: '<div />' },
      },
    ],
  });
  void router.push(`/apps/core/services/${service}`);
  return router;
}

async function mountDetail(service: string) {
  const router = makeRouter(service);
  await router.isReady();
  const wrapper = mount(CoreServiceDetail, {
    global: { plugins: [createPinia(), router] },
  });
  await flushPromises();
  return wrapper;
}

describe('CoreServiceDetail', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.spyOn(ContainersApi, 'list').mockResolvedValue([
      container('authelia'),
      container('caddy'),
    ]);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('does not render the Notifications panel for non-Authelia services', async () => {
    // Caddy has no notifier and never will; asking /api/sso/notifications
    // for it would be nonsense.
    const spy = vi.spyOn(SsoApi, 'notifications').mockResolvedValue([]);
    const wrapper = await mountDetail('caddy');
    expect(wrapper.find('[data-test="authelia-notifications"]').exists()).toBe(false);
    expect(spy).not.toHaveBeenCalled();
  });

  it('loads exactly 5 entries for Authelia', async () => {
    // 5 is a product decision (see CoreServiceDetail.vue): the backend
    // will happily go up to 20, but the panel is the last few only.
    // Pinned so a well-meaning refactor does not quietly widen it.
    const spy = vi.spyOn(SsoApi, 'notifications').mockResolvedValue([]);
    await mountDetail('authelia');
    expect(spy).toHaveBeenCalledWith(5);
  });

  it('renders an OTP entry with copyable code and revoke link', async () => {
    vi.spyOn(SsoApi, 'notifications').mockResolvedValue([OTP_NOTIFICATION]);
    const wrapper = await mountDetail('authelia');

    const otpBlock = wrapper.find('[data-test="authelia-notification-otp"]');
    expect(otpBlock.exists()).toBe(true);
    expect(otpBlock.text()).toContain('PHV9ZVAV');

    const revoke = wrapper.find('[data-test="authelia-notification-revoke"]');
    expect(revoke.exists()).toBe(true);
    expect(revoke.attributes('href')).toBe(
      'https://auth.aurora.local/revoke/one-time-code?id=abc',
    );

    // A link-only entry's block should not exist for an OTP row.
    expect(wrapper.find('[data-test="authelia-notification-link"]').exists()).toBe(false);
  });

  it('renders a link-only entry with the URL, no OTP block', async () => {
    vi.spyOn(SsoApi, 'notifications').mockResolvedValue([LINK_NOTIFICATION]);
    const wrapper = await mountDetail('authelia');

    const link = wrapper.find('[data-test="authelia-notification-link"]');
    expect(link.exists()).toBe(true);
    expect(link.text()).toContain('https://auth.aurora.local/webauthn/register?token=abc');

    expect(wrapper.find('[data-test="authelia-notification-otp"]').exists()).toBe(false);
  });

  it('empty state explains what triggers a notification', async () => {
    // Bruce landing on this page with no OTP in flight should see a
    // useful "here is when this fills in" note, not a blank card.
    vi.spyOn(SsoApi, 'notifications').mockResolvedValue([]);
    const wrapper = await mountDetail('authelia');

    const empty = wrapper.find('[data-test="authelia-notifications-empty"]');
    expect(empty.exists()).toBe(true);
    expect(empty.text().toLowerCase()).toContain('enroll');
  });

  it('surfaces the container image + status in the facts row', async () => {
    vi.spyOn(SsoApi, 'notifications').mockResolvedValue([]);
    const wrapper = await mountDetail('authelia');
    const facts = wrapper.find('[data-test="core-service-facts"]');
    expect(facts.text()).toContain('example/authelia:latest');
    expect(facts.text()).toContain('Up 5 minutes');
  });

  it('URL-typing a disabled service (aurora) redirects to Settings', async () => {
    // Aurora is disabled in the CORE_SERVICES catalogue: the grid card
    // hints "go to Settings", and this direct-URL path has to honour
    // the same contract or the hint becomes a lie the moment the
    // operator bookmarks the detail URL.
    vi.spyOn(SsoApi, 'notifications').mockResolvedValue([]);
    const router = makeRouter('aurora');
    const replace = vi.spyOn(router, 'replace');
    await router.isReady();
    mount(CoreServiceDetail, {
      global: { plugins: [createPinia(), router] },
    });
    await flushPromises();
    expect(replace).toHaveBeenCalledWith('/settings');
  });

  it('unknown service slug redirects back to /apps/core', async () => {
    vi.spyOn(SsoApi, 'notifications').mockResolvedValue([]);
    const router = makeRouter('nope');
    const replace = vi.spyOn(router, 'replace');
    await router.isReady();
    mount(CoreServiceDetail, {
      global: { plugins: [createPinia(), router] },
    });
    await flushPromises();
    expect(replace).toHaveBeenCalledWith('/apps/core');
  });

  // ── Open CTA ──────────────────────────────────────────────────────────────────────
  //
  // Bruce landed on /apps/core/services/stalwart and asked "how do I
  // actually reach the thing?" — the detail page carried no CTA into
  // the service's own UI, so operators had to guess the subdomain from
  // the manifest. Marketplace apps have had an Open CTA in their hero
  // since 1347c92; these tests pin the same contract for Core.

  async function mountWithDomain(service: string, domain: string | null) {
    setActivePinia(createPinia());
    if (domain) {
      // Seed the store rather than let it hydrate: the view treats an
      // absent domain as "hide the CTA" (a first-paint before /api/system
      // resolves would otherwise emit https://<subdomain>.//). Tests
      // that want the CTA visible have to populate this up front.
      const store = useSystemStore();
      store.info = {
        hostname: 'aurora',
        domain,
        lanIp: '192.168.0.110',
        kernel: '7.0.0',
        dockerVersion: '29.6',
        distro: 'Ubuntu 26.04 LTS',
        cpuCount: 4,
        uptimeSeconds: 100,
        capabilities: { notifications: false, customStacks: false },
      } as never;
    }
    vi.spyOn(ContainersApi, 'list').mockResolvedValue([
      container('authelia'),
      container('caddy'),
      container('stalwart'),
    ]);
    vi.spyOn(SsoApi, 'notifications').mockResolvedValue([]);
    const router = makeRouter(service);
    await router.isReady();
    const wrapper = mount(CoreServiceDetail, {
      global: { plugins: [router] },
    });
    await flushPromises();
    return wrapper;
  }

  it("renders Stalwart's Open CTA pointing at mail-admin.<domain>", async () => {
    const wrapper = await mountWithDomain('stalwart', 'aurora.local');
    const cta = wrapper.find('[data-test="core-service-open"]');
    expect(cta.exists()).toBe(true);
    expect(cta.attributes('href')).toBe('https://mail-admin.aurora.local/');
    expect(cta.text()).toContain('Open mail admin');
    // target=_blank + noopener are the same safety pair marketplace
    // apps use for their Open CTA; pin them so a refactor does not
    // quietly widen the surface.
    expect(cta.attributes('target')).toBe('_blank');
    expect(cta.attributes('rel')).toContain('noopener');
  });

  it("renders Authelia's Open CTA as 'Open Aurora SSO'", async () => {
    // openLabel override: the container is called 'authelia' but the
    // product name in the UI is 'Aurora SSO'. Pinned so a well-meaning
    // rename does not quietly diverge from the wizard's copy.
    const wrapper = await mountWithDomain('authelia', 'example.com');
    const cta = wrapper.find('[data-test="core-service-open"]');
    expect(cta.exists()).toBe(true);
    expect(cta.attributes('href')).toBe('https://auth.example.com/');
    expect(cta.text()).toContain('Open Aurora SSO');
  });

  it('hides the Open CTA for Caddy (no user-facing UI)', async () => {
    // Caddy's admin API is on :2019, unpublished. A CTA that 404s the
    // moment you click it reads worse than no CTA at all.
    const wrapper = await mountWithDomain('caddy', 'aurora.local');
    expect(wrapper.find('[data-test="core-service-open"]').exists()).toBe(false);
  });

  it('hides the Open CTA while the domain is not yet known', async () => {
    // First-paint before /api/system hydrates: rendering the CTA at
    // that point would emit https://mail-admin.// and be a broken link
    // for the first few hundred milliseconds after mount.
    const wrapper = await mountWithDomain('stalwart', null);
    expect(wrapper.find('[data-test="core-service-open"]').exists()).toBe(false);
  });

  it('hides the Open CTA when the container is not running', async () => {
    // A link into a service whose container is exited (or restarting)
    // just 502s under Caddy. Same visibility rule marketplace apps
    // apply in PackageDetail.openUrl.
    setActivePinia(createPinia());
    const store = useSystemStore();
    store.info = {
      hostname: 'aurora',
      domain: 'aurora.local',
      lanIp: '192.168.0.110',
      kernel: '7.0.0',
      dockerVersion: '29.6',
      distro: 'Ubuntu 26.04 LTS',
      cpuCount: 4,
      uptimeSeconds: 100,
      capabilities: { notifications: false, customStacks: false },
    } as never;
    vi.spyOn(ContainersApi, 'list').mockResolvedValue([
      { ...container('stalwart'), state: 'exited' },
    ]);
    vi.spyOn(SsoApi, 'notifications').mockResolvedValue([]);
    const router = makeRouter('stalwart');
    await router.isReady();
    const wrapper = mount(CoreServiceDetail, {
      global: { plugins: [router] },
    });
    await flushPromises();
    expect(wrapper.find('[data-test="core-service-open"]').exists()).toBe(false);
  });
});
