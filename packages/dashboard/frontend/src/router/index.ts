import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import { useAuthStore } from '@/stores/auth';

import AppShell from '@/components/layout/AppShell.vue';
import OnboardingShell from '@/components/layout/OnboardingShell.vue';

const routes: RouteRecordRaw[] = [
  { path: '/login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },

  {
    path: '/onboarding',
    component: OnboardingShell,
    meta: { public: true },
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
      { path: '', component: () => import('@/views/DashboardHome.vue') },
      { path: 'packages', component: () => import('@/views/PackagesList.vue') },
      { path: 'packages/:name', component: () => import('@/views/PackageDetail.vue') },
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

// Auth guard — public routes skip; everything else needs a session.
// Fail-open on network errors so the user can still reach /login.
router.beforeEach(async (to) => {
  if (to.meta.public) return true;
  const auth = useAuthStore();
  if (!auth.session) await auth.fetchSession();
  if (auth.session?.authenticated) return true;
  return { path: '/login', query: { from: to.fullPath } };
});
