import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { createRouter, createMemoryHistory } from 'vue-router';

import CoreServiceDetail from './CoreServiceDetail.vue';
import { ContainersApi, type ContainerInfo } from '@/api/containers';
import { SsoApi, type SsoNotification } from '@/api/sso';

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
});
