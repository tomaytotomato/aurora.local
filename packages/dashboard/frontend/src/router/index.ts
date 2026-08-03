import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { useOnboardingStore } from '@/stores/onboarding';

import AppShell from '@/components/layout/AppShell.vue';
import OnboardingShell from '@/components/layout/OnboardingShell.vue';

const routes: RouteRecordRaw[] = [
  { path: '/login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },

  {
    path: '/onboarding',
    component: OnboardingShell,
    meta: { public: true, onboarding: true },
    children: [
      { path: '', redirect: '/onboarding/welcome' },
      { path: 'welcome', component: () => import('@/views/onboarding/OnboardingWelcome.vue') },
      { path: 'admin', component: () => import('@/views/onboarding/OnboardingAdmin.vue') },
      { path: 'domain', component: () => import('@/views/onboarding/OnboardingDomain.vue') },
      { path: 'packages', component: () => import('@/views/onboarding/OnboardingPackages.vue') },
      { path: 'secrets', component: () => import('@/views/onboarding/OnboardingSecrets.vue') },
      { path: 'dns', component: () => import('@/views/onboarding/OnboardingDns.vue') },
      { path: 'tls', component: () => import('@/views/onboarding/OnboardingTls.vue') },
      { path: 'review', component: () => import('@/views/onboarding/OnboardingReview.vue') },
      { path: 'done', component: () => import('@/views/onboarding/OnboardingDone.vue') },
    ],
  },

  {
    path: '/',
    component: AppShell,
    children: [
      { path: '', component: () => import('@/views/DashboardHome.vue'), meta: { photoBg: true } },
      { path: 'packages', component: () => import('@/views/PackagesList.vue') },
      { path: 'packages/:name', component: () => import('@/views/PackageDetail.vue') },
      // B3 (v0.3): container log tail. Snapshot only.
      { path: 'containers/:id/logs', component: () => import('@/views/ContainerLogsView.vue') },
      { path: 'security', component: () => import('@/views/SecurityPosture.vue') },
      { path: 'users', component: () => import('@/views/UsersView.vue'), meta: { requiresAdmin: true } },
      { path: 'settings', component: () => import('@/views/SettingsView.vue') },
    ],
  },

  { path: '/:pathMatch(.*)*', redirect: '/' },
];

export const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 };
  },
});

// Guard order:
//   1. Onboarding not complete → force /onboarding/** (except /login).
//   2. Onboarding done + route is public → allow.
//   3. Otherwise require an authenticated session; else /login.
// Fail-open on network errors so the user can still reach /login.
router.beforeEach(async (to) => {
  const onboarding = useOnboardingStore();

  // One-shot hydration per SPA lifetime. Populates the full draft so
  // every step view can prefill from server truth on refresh.
  if (!onboarding.hydrated) {
    try {
      await onboarding.hydrate();
    } catch {
      // Backend unreachable — let the request through so the UI can show
      // its own error state instead of an infinite router loop.
      return true;
    }
  }

  const needsOnboarding =
    onboarding.status && (onboarding.status.bootstrap_mode || !onboarding.status.complete);

  if (needsOnboarding) {
    if (to.path.startsWith('/onboarding')) return true;
    // Allow /login too in case an admin was half-created and needs recovery.
    if (to.path === '/login') return true;
    // Resume where the server thinks the user left off, not always /welcome.
    const step = onboarding.status?.step ?? 'welcome';
    return { path: `/onboarding/${step}` };
  }

  // Onboarding done. Normal auth flow.
  if (to.meta.public) return true;

  const auth = useAuthStore();
  if (!auth.session) await auth.fetchSession();
  if (!auth.session?.authenticated) {
    return { path: '/login', query: { from: to.fullPath } };
  }
  // Phase D iter-10 (D9): admin-only routes bounce non-admin sessions
  // back to the dashboard home. Belt-and-braces — the sidebar link is
  // already role-gated, but a direct URL paste to /users from a USER
  // account would otherwise render an empty view and 403 on data load.
  if (to.meta.requiresAdmin && auth.session?.role !== 'admin') {
    return { path: '/' };
  }
  return true;
});
