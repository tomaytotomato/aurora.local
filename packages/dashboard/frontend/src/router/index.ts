import {
  createRouter,
  createWebHistory,
  type RouteLocationNormalized,
  type RouteRecordRaw,
} from 'vue-router';
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
      // 'packages' removed (2026-08-15): the interactive package-picker
      // step is gone — first-run installs the mandatory set only (see
      // MANDATORY_FIRST_RUN_PACKAGES in api/packages.ts) and everything
      // else is added afterwards from the Apps catalogue.
      //
      // 'secrets' removed: with no picker there was nothing to configure —
      // every secret is auto-generated at install and reviewable on an
      // app's Config screen. The reassurance moved to the Review step.
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
      //
      // Apps split into two pages (2026-08-07): Catalogue (Installed /
      // Marketplace) and Core, reached via SectionNav rather than a
      // nested tab strip — see PackagesCatalogue.vue / PackagesCore.vue.
      // Static children are listed before the dynamic `:name` so
      // `/apps/catalogue` and `/apps/core` never get swallowed by it
      // (vue-router ranks static segments first regardless, but the
      // order still reads correctly here).
      { path: 'apps', redirect: '/apps/catalogue' },
      { path: 'apps/catalogue', component: () => import('@/views/PackagesCatalogue.vue') },
      { path: 'apps/core', component: () => import('@/views/PackagesCore.vue') },
      // Your own compose files (2026-08-08). Deliberately a third page
      // rather than a filter on the catalogue: these are not curated and
      // must never read as though they were. See
      // docs/CUSTOM_STACK_DESIGN.md.
      { path: 'apps/custom', component: () => import('@/views/CustomStacks.vue') },
      { path: 'apps/:name', component: () => import('@/views/PackageDetail.vue') },
      { path: 'packages', redirect: '/apps/catalogue' },
      { path: 'packages/:name', redirect: (to) => `/apps/${to.params.name}` },
      // User management (2026-08-06).
      // VPN configuration — WireGuard-first (2026-08-06).
      { path: 'vpn', component: () => import('@/views/VpnView.vue') },
      // Backup (2026-08-08). Reports on Kopia rather than replacing it —
      // see docs/BACKUP_PAGE_DESIGN.md. Nav entry is gated on
      // capabilities.backup; the route itself stays reachable by URL so a
      // bookmark doesn't 404 into the catch-all redirect.
      { path: 'backup', component: () => import('@/views/BackupView.vue') },
      // Disks (2026-08-08): SMART health, mergerfs pool capacity and
      // SnapRAID parity freshness. See docs/DISKS_PAGE_DESIGN.md.
      { path: 'disks', component: () => import('@/views/DisksView.vue') },
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
//   2. Onboarding done + the URL is still an onboarding route → the
//      dashboard, not the wizard (see onboardingGuard below).
//   3. Onboarding done + route is public → allow.
//   4. Otherwise require an authenticated session; else /login.
// Fail-open on network errors so the user can still reach /login.
export async function onboardingGuard(
  to: RouteLocationNormalized,
): Promise<boolean | { path: string; query?: Record<string, string> }> {
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

  // Onboarding is done. Its own routes are still registered (so a stale
  // tab mid-wizard doesn't 404), but there is nothing left in them to do —
  // walking back in via the browser's back button or a stale bookmark
  // would let someone retry steps (e.g. "start services") against a box
  // that has already launched. The backend's own guardMidOnboarding()
  // refuses the mutating calls those steps would make (409), but bouncing
  // here means the operator never sees a broken wizard screen in the
  // first place.
  if (to.meta.onboarding) {
    return { path: '/' };
  }

  // Normal auth flow.
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
}

router.beforeEach(onboardingGuard);
