import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { createRouter, createMemoryHistory } from 'vue-router';

import CoreServiceDetail from './CoreServiceDetail.vue';
import { ContainersApi, type ContainerInfo } from '@/api/containers';
import { SsoApi, type SsoNotification } from '@/api/sso';
import { StalwartApi } from '@/api/stalwart';
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

  // ── Stalwart recovery-admin reveal panel ─────────────────────────────────────
  //
  // The whole panel exists because Bruce landed on the mail-admin
  // console for the first time and had to shell in to read the
  // recovery-admin password out of packages/core/.env. These tests
  // pin the shape that keeps that story from regressing.

  async function mountStalwartPanel(): Promise<ReturnType<typeof mount>> {
    setActivePinia(createPinia());
    vi.spyOn(ContainersApi, 'list').mockResolvedValue([container('stalwart')]);
    vi.spyOn(SsoApi, 'notifications').mockResolvedValue([]);
    const router = makeRouter('stalwart');
    await router.isReady();
    const wrapper = mount(CoreServiceDetail, {
      global: { plugins: [router] },
    });
    await flushPromises();
    return wrapper;
  }

  it('renders the reveal panel only for Stalwart', async () => {
    vi.spyOn(SsoApi, 'notifications').mockResolvedValue([]);
    const caddy = await mountDetail('caddy');
    expect(caddy.find('[data-test="stalwart-admin-panel"]').exists()).toBe(false);

    const stalwart = await mountStalwartPanel();
    expect(stalwart.find('[data-test="stalwart-admin-panel"]').exists()).toBe(true);
  });

  it('does not fetch the secret on mount', async () => {
    // Lazy on purpose: an operator who never clicks Reveal never has
    // the plaintext in memory. Same reasoning UsersView applies to
    // its issued-password modal.
    const spy = vi.spyOn(StalwartApi, 'adminSecret').mockResolvedValue({
      username: 'admin',
      secret: 'unused-during-mount',
      source: 'ENV',
    });
    await mountStalwartPanel();
    expect(spy).not.toHaveBeenCalled();
  });

  it('clicking Reveal fetches the secret and shows it', async () => {
    vi.spyOn(StalwartApi, 'adminSecret').mockResolvedValue({
      username: 'admin',
      secret: 'abc123-strong-value',
      source: 'ENV',
    });
    const wrapper = await mountStalwartPanel();

    // Pre-click: masked value visible, real value not on the page.
    const before = wrapper.find('[data-test="stalwart-admin-secret"]').text();
    expect(before).not.toContain('abc123-strong-value');

    await wrapper.find('[data-test="stalwart-admin-reveal"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-test="stalwart-admin-secret"]').text())
      .toContain('abc123-strong-value');
  });

  it('Reveal button toggles to Hide once revealed, and back again', async () => {
    vi.spyOn(StalwartApi, 'adminSecret').mockResolvedValue({
      username: 'admin',
      secret: 'abc123-strong-value',
      source: 'ENV',
    });
    const wrapper = await mountStalwartPanel();

    const btn = wrapper.find('[data-test="stalwart-admin-reveal"]');
    expect(btn.text()).toBe('Reveal');

    await btn.trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-test="stalwart-admin-reveal"]').text()).toBe('Hide');

    await wrapper.find('[data-test="stalwart-admin-reveal"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-test="stalwart-admin-reveal"]').text()).toBe('Reveal');
    // Value hidden again.
    expect(wrapper.find('[data-test="stalwart-admin-secret"]').text())
      .not.toContain('abc123-strong-value');
  });

  it('shows the rotate-me warning when source=DEFAULT', async () => {
    vi.spyOn(StalwartApi, 'adminSecret').mockResolvedValue({
      username: 'admin',
      secret: 'aurora-change-me',
      source: 'DEFAULT',
    });
    const wrapper = await mountStalwartPanel();
    await wrapper.find('[data-test="stalwart-admin-reveal"]').trigger('click');
    await flushPromises();

    const warn = wrapper.find('[data-test="stalwart-admin-default-warning"]');
    expect(warn.exists()).toBe(true);
    expect(warn.text().toLowerCase()).toContain('compose fallback');
    expect(warn.text()).toContain('STALWART_ADMIN_SECRET');
  });

  it('does not show the rotate-me warning for a real ENV value', async () => {
    vi.spyOn(StalwartApi, 'adminSecret').mockResolvedValue({
      username: 'admin',
      secret: 'abc123-strong-value',
      source: 'ENV',
    });
    const wrapper = await mountStalwartPanel();
    await wrapper.find('[data-test="stalwart-admin-reveal"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-test="stalwart-admin-default-warning"]').exists())
      .toBe(false);
  });

  it('403 from the endpoint yields a role-specific error', async () => {
    // A logged-in USER can reach /apps/core/services/stalwart but the
    // reveal endpoint refuses. The error copy has to say why so the
    // operator does not just re-click hoping for a different answer.
    const err = { response: { status: 403 } };
    vi.spyOn(StalwartApi, 'adminSecret').mockRejectedValue(err);
    const wrapper = await mountStalwartPanel();
    await wrapper.find('[data-test="stalwart-admin-reveal"]').trigger('click');
    await flushPromises();

    const errNode = wrapper.find('[data-test="stalwart-admin-error"]');
    expect(errNode.exists()).toBe(true);
    expect(errNode.text().toLowerCase()).toContain('admin');
  });

  it('caches the fetched secret — a re-reveal does not re-fetch', async () => {
    // A follow-up reveal on the same visit must not trigger another
    // GET; caching is what makes Hide + Reveal cheap.
    const spy = vi.spyOn(StalwartApi, 'adminSecret').mockResolvedValue({
      username: 'admin',
      secret: 'abc123-strong-value',
      source: 'ENV',
    });
    const wrapper = await mountStalwartPanel();

    await wrapper.find('[data-test="stalwart-admin-reveal"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-test="stalwart-admin-reveal"]').trigger('click'); // Hide
    await flushPromises();
    await wrapper.find('[data-test="stalwart-admin-reveal"]').trigger('click'); // Reveal again
    await flushPromises();

    expect(spy).toHaveBeenCalledTimes(1);
  });

  // ── Edit-password flow ───────────────────────────────────────────────
  //
  // Bruce landed on Stalwart's mail-admin console, saw the compose
  // fallback, and had no way to rotate it from the dashboard — he
  // had to shell in and edit packages/core/.env. These tests pin the
  // shape that lets the reveal panel own that rotation end to end.

  it('does not show the Edit button before Reveal', async () => {
    // The whole point of Edit is "change what I can see". Before
    // Reveal there is nothing to compare against and the panel still
    // shows the masked bullets, so the CTA would misdirect.
    vi.spyOn(StalwartApi, 'adminSecret').mockResolvedValue({
      username: 'admin',
      secret: 'abc123-strong-value',
      source: 'ENV',
    });
    const wrapper = await mountStalwartPanel();
    expect(wrapper.find('[data-test="stalwart-admin-edit"]').exists()).toBe(false);
  });

  it('shows the Edit button once revealed', async () => {
    vi.spyOn(StalwartApi, 'adminSecret').mockResolvedValue({
      username: 'admin',
      secret: 'abc123-strong-value',
      source: 'ENV',
    });
    const wrapper = await mountStalwartPanel();
    await wrapper.find('[data-test="stalwart-admin-reveal"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-test="stalwart-admin-edit"]').exists()).toBe(true);
  });

  it('does not show the Edit button on non-Stalwart services', async () => {
    // Rotation is Stalwart-specific today — the other core services
    // do not have an equivalent reveal panel and would render an
    // orphaned button that goes nowhere.
    vi.spyOn(SsoApi, 'notifications').mockResolvedValue([]);
    const wrapper = await mountDetail('caddy');
    expect(wrapper.find('[data-test="stalwart-admin-edit"]').exists()).toBe(false);
  });

  async function openEditForm(): Promise<ReturnType<typeof mount>> {
    vi.spyOn(StalwartApi, 'adminSecret').mockResolvedValue({
      username: 'admin',
      secret: 'abc123-strong-value',
      source: 'ENV',
    });
    const wrapper = await mountStalwartPanel();
    await wrapper.find('[data-test="stalwart-admin-reveal"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-test="stalwart-admin-edit"]').trigger('click');
    await flushPromises();
    return wrapper;
  }

  it('Save calls the API with the new secret and refreshes the credential', async () => {
    // Two contracts: (1) the PUT is fired with the new value; (2) the
    // panel refetches through adminSecret() so a stale cached value
    // cannot silently lie about the outcome.
    vi.spyOn(StalwartApi, 'adminSecret')
      .mockResolvedValueOnce({
        username: 'admin',
        secret: 'abc123-strong-value',
        source: 'ENV',
      })
      .mockResolvedValueOnce({
        username: 'admin',
        secret: 'brand-new-strong-value',
        source: 'ENV',
      });
    const put = vi.spyOn(StalwartApi, 'updateAdminSecret').mockResolvedValue(undefined);

    const wrapper = await mountStalwartPanel();
    await wrapper.find('[data-test="stalwart-admin-reveal"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-test="stalwart-admin-edit"]').trigger('click');
    await flushPromises();

    const newInput = wrapper.find('[data-test="stalwart-admin-new-password"]');
    const confirmInput = wrapper.find('[data-test="stalwart-admin-confirm-password"]');
    await newInput.setValue('brand-new-strong-value');
    await confirmInput.setValue('brand-new-strong-value');
    await wrapper.find('[data-test="stalwart-admin-save"]').trigger('click');
    await flushPromises();

    expect(put).toHaveBeenCalledWith('brand-new-strong-value');

    // Panel is back in read-only reveal state and shows the new value.
    expect(wrapper.find('[data-test="stalwart-admin-edit-form"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="stalwart-admin-secret"]').text())
      .toContain('brand-new-strong-value');
    expect(wrapper.find('[data-test="stalwart-admin-save-success"]').exists()).toBe(true);
  });

  it('the success alert mentions the container recreate step', async () => {
    // Rotating the .env alone is not enough — compose interpolates
    // env at container-create time and a live Stalwart container
    // keeps its old value. The success copy has to say so, or the
    // operator will assume the change took effect immediately and
    // then panic when the old password still works in mail-admin.
    vi.spyOn(StalwartApi, 'adminSecret').mockResolvedValue({
      username: 'admin',
      secret: 'brand-new-strong-value',
      source: 'ENV',
    });
    vi.spyOn(StalwartApi, 'updateAdminSecret').mockResolvedValue(undefined);

    const wrapper = await mountStalwartPanel();
    await wrapper.find('[data-test="stalwart-admin-reveal"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-test="stalwart-admin-edit"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-test="stalwart-admin-new-password"]').setValue('brand-new-strong-value');
    await wrapper.find('[data-test="stalwart-admin-confirm-password"]').setValue('brand-new-strong-value');
    await wrapper.find('[data-test="stalwart-admin-save"]').trigger('click');
    await flushPromises();

    const success = wrapper.find('[data-test="stalwart-admin-save-success"]');
    expect(success.exists()).toBe(true);
    expect(success.text().toLowerCase()).toContain('recreated');
    expect(success.text()).toContain('./scripts/up.sh core');
  });

  it('client-side mismatch blocks the API call', async () => {
    // A form-fill mistake must never leave an audit row on the box.
    const put = vi.spyOn(StalwartApi, 'updateAdminSecret').mockResolvedValue(undefined);
    const wrapper = await openEditForm();

    await wrapper.find('[data-test="stalwart-admin-new-password"]').setValue('a-strong-value-here');
    await wrapper.find('[data-test="stalwart-admin-confirm-password"]').setValue('a-different-value');
    await wrapper.find('[data-test="stalwart-admin-save"]').trigger('click');
    await flushPromises();

    expect(put).not.toHaveBeenCalled();
    const err = wrapper.find('[data-test="stalwart-admin-save-error"]');
    expect(err.exists()).toBe(true);
    expect(err.text().toLowerCase()).toContain('match');
  });

  it('client-side too-short blocks the API call', async () => {
    // 12-char floor mirrors the backend's @Size(min = 12) and the
    // change-password endpoint. Catching it here means the operator
    // gets an instant answer.
    const put = vi.spyOn(StalwartApi, 'updateAdminSecret').mockResolvedValue(undefined);
    const wrapper = await openEditForm();

    await wrapper.find('[data-test="stalwart-admin-new-password"]').setValue('short');
    await wrapper.find('[data-test="stalwart-admin-confirm-password"]').setValue('short');
    await wrapper.find('[data-test="stalwart-admin-save"]').trigger('click');
    await flushPromises();

    expect(put).not.toHaveBeenCalled();
    const err = wrapper.find('[data-test="stalwart-admin-save-error"]');
    expect(err.exists()).toBe(true);
    expect(err.text()).toContain('12');
  });

  it('400 from the backend renders inline in the edit form', async () => {
    // Belt + braces: even if the client-side floor is bypassed, a
    // backend 400 must not become a global toast — this is a form
    // submit and the error copy belongs next to the fields.
    vi.spyOn(StalwartApi, 'updateAdminSecret').mockRejectedValue({
      response: { status: 400, data: { message: 'server said no' } },
    });
    const wrapper = await openEditForm();
    await wrapper.find('[data-test="stalwart-admin-new-password"]').setValue('a-strong-value-here');
    await wrapper.find('[data-test="stalwart-admin-confirm-password"]').setValue('a-strong-value-here');
    await wrapper.find('[data-test="stalwart-admin-save"]').trigger('click');
    await flushPromises();

    const err = wrapper.find('[data-test="stalwart-admin-save-error"]');
    expect(err.exists()).toBe(true);
    expect(err.text()).toContain('server said no');
    // Edit form stays open so the operator can retry.
    expect(wrapper.find('[data-test="stalwart-admin-edit-form"]').exists()).toBe(true);
  });

  it('403 from the backend renders the admin-only copy in the edit form', async () => {
    // Same reasoning as the read side: a logged-in USER can reach the
    // page, but the write refuses. Copy says why so re-clicking is
    // obvious as futile.
    vi.spyOn(StalwartApi, 'updateAdminSecret').mockRejectedValue({
      response: { status: 403 },
    });
    const wrapper = await openEditForm();
    await wrapper.find('[data-test="stalwart-admin-new-password"]').setValue('a-strong-value-here');
    await wrapper.find('[data-test="stalwart-admin-confirm-password"]').setValue('a-strong-value-here');
    await wrapper.find('[data-test="stalwart-admin-save"]').trigger('click');
    await flushPromises();

    const err = wrapper.find('[data-test="stalwart-admin-save-error"]');
    expect(err.exists()).toBe(true);
    expect(err.text().toLowerCase()).toContain('admin');
  });

  it('Cancel closes the form and keeps the current revealed secret', async () => {
    // Cancel is "never mind", not "forget everything". The reveal
    // panel above the form must still show the original value.
    const wrapper = await openEditForm();

    await wrapper.find('[data-test="stalwart-admin-new-password"]').setValue('a-strong-value-here');
    await wrapper.find('[data-test="stalwart-admin-cancel"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-test="stalwart-admin-edit-form"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="stalwart-admin-secret"]').text())
      .toContain('abc123-strong-value');
  });

  // ─── Create mailbox panel ────────────────────────────────────

  it('renders the create-mailbox panel only for Stalwart', async () => {
    const caddy = await mountDetail('caddy');
    expect(caddy.find('[data-test="stalwart-mailbox-panel"]').exists()).toBe(false);

    const stalwart = await mountWithDomain('stalwart', 'aurora.local');
    expect(stalwart.find('[data-test="stalwart-mailbox-panel"]').exists()).toBe(true);
    // The address preview shows the box's own domain.
    expect(stalwart.find('[data-test="stalwart-mailbox-panel"]').text()).toContain('aurora.local');
  });

  it('disables Create until the local part is valid', async () => {
    const wrapper = await mountWithDomain('stalwart', 'aurora.local');
    const btn = () => wrapper.find('[data-test="stalwart-mailbox-create"]');
    // Empty -> disabled.
    expect(btn().attributes('disabled')).toBeDefined();
    // Invalid (space + capital + bang) -> still disabled.
    await wrapper.find('[data-test="stalwart-mailbox-localpart"]').setValue('Bad Name!');
    expect(btn().attributes('disabled')).toBeDefined();
    // Valid -> enabled.
    await wrapper.find('[data-test="stalwart-mailbox-localpart"]').setValue('bruce');
    expect(btn().attributes('disabled')).toBeUndefined();
  });

  it('creates a mailbox and shows the one-time password', async () => {
    const spy = vi.spyOn(StalwartApi, 'createMailbox').mockResolvedValue({
      email: 'bruce@aurora.local',
      password: 'damson-onyx-bison-pebble',
    });
    const wrapper = await mountWithDomain('stalwart', 'aurora.local');

    await wrapper.find('[data-test="stalwart-mailbox-localpart"]').setValue('bruce');
    await wrapper.find('[data-test="stalwart-mailbox-create"]').trigger('click');
    await flushPromises();

    expect(spy).toHaveBeenCalledWith('bruce');
    // The one-time result panel replaces the form and shows both fields.
    const result = wrapper.find('[data-test="stalwart-mailbox-result"]');
    expect(result.exists()).toBe(true);
    expect(wrapper.find('[data-test="stalwart-mailbox-email"]').text()).toBe('bruce@aurora.local');
    expect(wrapper.find('[data-test="stalwart-mailbox-password"]').text()).toBe('damson-onyx-bison-pebble');
    // The form is hidden while the password is on screen.
    expect(wrapper.find('[data-test="stalwart-mailbox-create"]').exists()).toBe(false);
  });

  it('surfaces a 409 as a friendly already-exists message', async () => {
    vi.spyOn(StalwartApi, 'createMailbox').mockRejectedValue({ response: { status: 409 } });
    const wrapper = await mountWithDomain('stalwart', 'aurora.local');

    await wrapper.find('[data-test="stalwart-mailbox-localpart"]').setValue('bruce');
    await wrapper.find('[data-test="stalwart-mailbox-create"]').trigger('click');
    await flushPromises();

    const err = wrapper.find('[data-test="stalwart-mailbox-error"]');
    expect(err.exists()).toBe(true);
    expect(err.text().toLowerCase()).toContain('already exists');
    // No result panel on failure.
    expect(wrapper.find('[data-test="stalwart-mailbox-result"]').exists()).toBe(false);
  });

  it('surfaces a 502 as a mail-server-unreachable message', async () => {
    vi.spyOn(StalwartApi, 'createMailbox').mockRejectedValue({ response: { status: 502 } });
    const wrapper = await mountWithDomain('stalwart', 'aurora.local');

    await wrapper.find('[data-test="stalwart-mailbox-localpart"]').setValue('bruce');
    await wrapper.find('[data-test="stalwart-mailbox-create"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-test="stalwart-mailbox-error"]').text().toLowerCase())
      .toContain('not reachable');
  });

  it('dismisses the one-time password panel back to the form', async () => {
    vi.spyOn(StalwartApi, 'createMailbox').mockResolvedValue({
      email: 'bruce@aurora.local',
      password: 'damson-onyx-bison-pebble',
    });
    const wrapper = await mountWithDomain('stalwart', 'aurora.local');

    await wrapper.find('[data-test="stalwart-mailbox-localpart"]').setValue('bruce');
    await wrapper.find('[data-test="stalwart-mailbox-create"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-test="stalwart-mailbox-result"]').exists()).toBe(true);

    await wrapper.find('[data-test="stalwart-mailbox-done"]').trigger('click');
    await flushPromises();
    // Back to the form, no lingering password.
    expect(wrapper.find('[data-test="stalwart-mailbox-result"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="stalwart-mailbox-create"]').exists()).toBe(true);
  });
});
