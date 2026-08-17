import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { createMemoryHistory, createRouter, type RouteLocationNormalized } from 'vue-router';

import { onboardingGuard } from './index';
import { OnboardingApi, type OnboardingDraft } from '@/api/onboarding';
import { AuthApi, type Session } from '@/api/auth';
import { useOnboardingStore } from '@/stores/onboarding';
import { useAuthStore } from '@/stores/auth';

/**
 * The owner's second first-run complaint: after finishing the wizard and
 * starting services, he navigated back to an onboarding URL and could
 * still see (and try to use) wizard steps against a box that had already
 * launched.
 *
 * Before this fix `needsOnboarding` went false once the wizard completed,
 * and the guard's very next check was `if (to.meta.public) return true` —
 * every /onboarding/* route carries `meta.public: true` (so it works
 * pre-auth during the wizard itself), so that check let a completed
 * onboarding route straight through with no further checks at all. These
 * tests pin the fix: a completed box redirects an onboarding URL to '/'
 * before the public-route check ever runs.
 */

const COMPLETE_DRAFT: OnboardingDraft = {
  complete: true,
  bootstrap_mode: false,
  step: 'done',
  admin_username: 'admin',
  domain: 'aurora.local',
  enabled_packages: ['core', 'storage'],
  dns_mode: null,
};

const INCOMPLETE_DRAFT: OnboardingDraft = {
  complete: false,
  bootstrap_mode: false,
  step: 'domain',
  admin_username: 'admin',
  domain: null,
  enabled_packages: [],
  dns_mode: null,
};

const AUTHENTICATED_SESSION: Session = {
  authenticated: true,
  username: 'admin',
  passkeyEnrolled: false,
  tz: 'UTC',
  role: 'admin',
};

function locationFor(path: string, meta: Record<string, unknown> = {}): RouteLocationNormalized {
  return {
    path,
    fullPath: path,
    meta,
    matched: [],
    params: {},
    query: {},
    hash: '',
    redirectedFrom: undefined,
    name: undefined,
  } as unknown as RouteLocationNormalized;
}

// A real router/matcher is used for the onboarding-route redirects test
// below, since `to.meta` for a nested child route is only reliably
// populated (merged from every matched record) by actually resolving a
// path through vue-router — hand-building `meta` for the other cases is
// fine because those assertions don't depend on the merge behaviour.
function buildRealRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div>dashboard</div>' } },
      { path: '/login', component: { template: '<div>login</div>' }, meta: { public: true } },
      {
        path: '/onboarding',
        component: { template: '<div><router-view /></div>' },
        meta: { public: true, onboarding: true },
        children: [
          { path: 'welcome', component: { template: '<div>welcome</div>' } },
          { path: 'review', component: { template: '<div>review</div>' } },
        ],
      },
    ],
  });
}

beforeEach(() => {
  setActivePinia(createPinia());
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('onboardingGuard', () => {
  it('sends a completed onboarding route to the dashboard, not into the wizard', async () => {
    vi.spyOn(OnboardingApi, 'get').mockResolvedValue(COMPLETE_DRAFT);
    vi.spyOn(AuthApi, 'session').mockResolvedValue(AUTHENTICATED_SESSION);

    const router = buildRealRouter();
    await router.push('/onboarding/review');
    await router.isReady();
    const to = router.currentRoute.value;

    const result = await onboardingGuard(to);

    expect(result).toEqual({ path: '/' });
  });

  it('still lets a genuinely incomplete onboarding reach its own routes', async () => {
    vi.spyOn(OnboardingApi, 'get').mockResolvedValue(INCOMPLETE_DRAFT);

    const router = buildRealRouter();
    await router.push('/onboarding/welcome');
    await router.isReady();
    const to = router.currentRoute.value;

    const result = await onboardingGuard(to);

    expect(result).toBe(true);
  });

  it('leaves normal authenticated navigation alone once onboarding is done', async () => {
    vi.spyOn(OnboardingApi, 'get').mockResolvedValue(COMPLETE_DRAFT);
    vi.spyOn(AuthApi, 'session').mockResolvedValue(AUTHENTICATED_SESSION);

    const to = locationFor('/apps/catalogue', {});
    const result = await onboardingGuard(to);

    expect(result).toBe(true);
    expect(useAuthStore().session?.authenticated).toBe(true);
  });

  it('does not re-hydrate on every navigation once the guard has already run once', async () => {
    const getSpy = vi.spyOn(OnboardingApi, 'get').mockResolvedValue(COMPLETE_DRAFT);
    vi.spyOn(AuthApi, 'session').mockResolvedValue(AUTHENTICATED_SESSION);

    await onboardingGuard(locationFor('/apps/catalogue'));
    await onboardingGuard(locationFor('/vpn'));

    expect(getSpy).toHaveBeenCalledTimes(1);
  });

  it('reflects markOnboardingComplete() immediately, without a fresh hydrate', async () => {
    // Regression guard for the store fix this depends on: hydrate() only
    // runs once per SPA lifetime, so the Done page calls
    // markOnboardingComplete() straight after POST /complete succeeds.
    // If the guard read a stale cached draft instead of `onboarding.status`
    // (which is derived from the same `draft` ref), this would still see
    // complete: false and route the "Go to my dashboard" click straight
    // back into the wizard.
    vi.spyOn(OnboardingApi, 'get').mockResolvedValue(INCOMPLETE_DRAFT);
    vi.spyOn(AuthApi, 'session').mockResolvedValue(AUTHENTICATED_SESSION);

    await onboardingGuard(locationFor('/onboarding/done', { public: true, onboarding: true }));
    useOnboardingStore().markOnboardingComplete();

    const result = await onboardingGuard(locationFor('/onboarding/done', { public: true, onboarding: true }));

    expect(result).toEqual({ path: '/' });
  });
});
