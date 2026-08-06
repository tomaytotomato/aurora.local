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
    // The aurora photo background is app-wide now (see AppShell.vue). Meta
    // lives on the parent so every child inherits it via the merged
    // route.meta — views read `route.meta.photoBg` to light their headers.
    meta: { photoBg: true },
    children: [
      { path: '', component: () => import('@/views/DashboardHome.vue') },
      // "Packages" is now "Apps" in the UI (2026-08-06). The route moved
      // to /apps; the old /packages paths redirect so bookmarks and any
      // in-flight links keep working. The wire (/api/packages) is
      // unchanged — this is a user-facing rename only.
      { path: 'apps', component: () => import('@/views/PackagesList.vue') },
      { path: 'apps/:name', component: () => import('@/views/PackageDetail.vue') },
      { path: 'packages', redirect: '/apps' },
      { path: 'packages/:name', redirect: (to) => `/apps/${to.params.name}` },
      // User management (2026-08-06).
      { path: 'users', component: () => import('@/views/UsersView.vue') },
      // VPN configuration — WireGuard-first (2026-08-06).
      { path: 'vpn', component: () => import('@/views/VpnView.vue') },
      // B3 (v0.3): container log tail. Snapshot only.
      { path: 'containers/:id/logs', component: () => import('@/views/ContainerLogsView.vue') },
      { path: 'security', component: () => import('@/views/SecurityPosture.vue') },
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
  if (auth.session?.authenticated) return true;
  return { path: '/login', query: { from: to.fullPath } };
});
