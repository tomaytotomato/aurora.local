import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { createRouter, createMemoryHistory } from 'vue-router';
import type { AxiosAdapter, AxiosResponse } from 'axios';
import LoginView from './LoginView.vue';
import { http } from '@/api/client';

/**
 * Post-login navigation.
 *
 * Regression under test: the auth guard bounces an unauthenticated user
 * to `/login?from=<intended path>` (router/index.ts), but submit() ended
 * in a hard-coded `router.push('/')`. The destination was parked and
 * then thrown away, so Bruce's report — sign in at
 * /login?from=/apps/roundcube, land on the dashboard home — reproduced
 * every time.
 *
 * The companion concern is that `from` is attacker-controllable, so
 * these also pin that a hostile value degrades to '/' rather than
 * navigating off-origin. The exhaustive vetting cases live in
 * lib/safeRedirect.spec.ts; here we prove the view is actually wired to
 * that helper.
 *
 * Custom axios adapter rather than MSW, matching UsersView.spec.ts /
 * PackageDetail.spec.ts.
 */

function installAdapter(): void {
  const adapter: AxiosAdapter = (config) => {
    const url = config.url ?? '';
    // Onboarding hydrate on mount: a completed box, so the login card
    // renders without the "Start onboarding" CTA.
    if (url.includes('/onboarding')) {
      return Promise.resolve({
        data: { complete: true, bootstrap_mode: false, step: 'done' },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      } as AxiosResponse);
    }
    // Successful sign-in.
    if (url.includes('/auth/login')) {
      return Promise.resolve({
        data: { authenticated: true, username: 'bruce', passkeyEnrolled: false, tz: null, role: 'admin' },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      } as AxiosResponse);
    }
    return Promise.resolve({ data: {}, status: 200, statusText: 'OK', headers: {}, config } as AxiosResponse);
  };
  http.defaults.adapter = adapter;
}

/** Mount the login view at `/login` with the given ?from= value. */
async function mountLogin(from?: string) {
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div>home</div>' } },
      { path: '/login', component: LoginView },
      { path: '/apps/:name', component: { template: '<div>app</div>' } },
      { path: '/:pathMatch(.*)*', redirect: '/' },
    ],
  });
  const target = from === undefined ? '/login' : `/login?from=${encodeURIComponent(from)}`;
  await router.push(target);
  await router.isReady();
  const w = mount(LoginView, { global: { plugins: [pinia, router] } });
  await flushPromises();
  return { w, router };
}

/** Fill credentials and submit the form. */
async function signIn(w: Awaited<ReturnType<typeof mountLogin>>['w']): Promise<void> {
  await w.find('#username').setValue('bruce');
  await w.find('#password').setValue('hunter2');
  await w.find('form').trigger('submit');
  await flushPromises();
}

beforeEach(() => {
  installAdapter();
});

afterEach(() => {
  delete (http.defaults as { adapter?: AxiosAdapter }).adapter;
  vi.restoreAllMocks();
});

describe('LoginView post-login redirect', () => {
  it('resumes the page the guard interrupted', async () => {
    const { w, router } = await mountLogin('/apps/roundcube');
    await signIn(w);
    expect(router.currentRoute.value.fullPath).toBe('/apps/roundcube');
  });

  it('goes to the dashboard when there is no ?from=', async () => {
    const { w, router } = await mountLogin();
    await signIn(w);
    expect(router.currentRoute.value.fullPath).toBe('/');
  });

  it('refuses to forward to another origin after a real sign-in', async () => {
    const { w, router } = await mountLogin('https://evil.tld/harvest');
    await signIn(w);
    expect(router.currentRoute.value.fullPath).toBe('/');
  });

  it('refuses a protocol-relative destination', async () => {
    const { w, router } = await mountLogin('//evil.tld/harvest');
    await signIn(w);
    expect(router.currentRoute.value.fullPath).toBe('/');
  });

  it('does not bounce back to /login', async () => {
    const { w, router } = await mountLogin('/login');
    await signIn(w);
    expect(router.currentRoute.value.fullPath).toBe('/');
  });
});

/**
 * The full session-expiry round-trip, end to end.
 *
 * Bruce's exact sequence, as one test: authenticated on /apps/roundcube
 * -> the aurora container is recreated and his session dies -> a request
 * 401s -> the interceptor bounces him to login -> he signs in -> he
 * should be back on /apps/roundcube, not the dashboard home.
 *
 * This spans both defects on purpose. The unit-level halves are covered
 * in api/client.spec.ts (the bounce sets ?from=) and above (the view
 * honours it); neither alone proves the round-trip closes, because the
 * original bug needed only one of the two links to be broken for the
 * user-visible symptom to appear.
 */
describe('LoginView session-expiry round-trip', () => {
  it('returns the user to the page whose request 401d', async () => {
    // Step 1-3: the interceptor has already bounced the browser and
    // parked the interrupted page in ?from=. That is the URL the SPA
    // boots at after the redirect.
    const interrupted = '/apps/roundcube';
    const { w, router } = await mountLogin(interrupted);

    // Precondition: the login page really is carrying the destination.
    expect(router.currentRoute.value.query.from).toBe(interrupted);

    // Step 4-5: sign in successfully.
    await signIn(w);

    expect(router.currentRoute.value.fullPath).toBe(interrupted);
    expect(router.currentRoute.value.fullPath).not.toBe('/');
  });
});

/**
 * Regression pin for the fake-passkey button removal (QA sweep,
 * 27 Aug 2026). Auth plan v2 §5 M1 said "Shipping a button that lies
 * is worse than shipping nothing." The button used to sit next to
 * Sign in and only surface a 4-second toast. Post-removal, the login
 * page should offer exactly one door: the password form.
 */
describe('LoginView UI surface', () => {
  it('does not render a passkey button until real passkey login lands', async () => {
    const { w } = await mountLogin();
    const buttons = w.findAll('button').map((b) => b.text().toLowerCase());
    // Sign-in button still present.
    expect(buttons).toContain('sign in');
    // No passkey-anything on this page.
    expect(buttons.some((t) => t.includes('passkey'))).toBe(false);
    expect(w.text().toLowerCase()).not.toContain('passkey');
  });
});
