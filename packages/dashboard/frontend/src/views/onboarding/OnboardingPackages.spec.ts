import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { createRouter, createMemoryHistory } from 'vue-router';

import OnboardingPackages from './OnboardingPackages.vue';
import { PackagesApi, type PackageSummary } from '@/api/packages';
import { OnboardingApi, type InstallPlan } from '@/api/onboarding';
import { useOnboardingStore } from '@/stores/onboarding';

/**
 * Covers two things the owner asked for directly:
 *
 * 1. The picker no longer carries its own hardcoded fallback catalogue.
 *    That fallback's descriptions had already drifted from the real
 *    packages/*manifest.yml wording (see dev/notes/unify-picker-catalogue-
 *    progress.md) — a stale-but-confident description is worse than an
 *    honest loading/error state, so a failed fetch now shows a retry
 *    rather than silently substituting hand-written copy.
 *
 * 2. Authelia (identity) groups onto the Core tab alongside the other
 *    mandatory packages (core, storage), driven by isCorePackage() rather
 *    than a second hardcoded list, and there is no separate Identity/Auth
 *    tab. No tab can render empty, and a mandatory package can't vanish
 *    from every tab.
 */

function pkg(over: Partial<PackageSummary> & { name: string }): PackageSummary {
  return {
    category: 'productivity',
    description: 'A package.',
    enabled: false,
    running: false,
    ...over,
  };
}

const CATALOGUE: PackageSummary[] = [
  pkg({ name: 'core', title: 'Core', category: 'core', description: 'Caddy.' }),
  pkg({ name: 'identity', title: 'Identity', category: 'identity', description: 'Authelia.' }),
  pkg({ name: 'storage', title: 'Storage', category: 'storage', description: 'Samba.' }),
  pkg({ name: 'privacy', title: 'Privacy', category: 'privacy', description: 'AdGuard.' }),
  pkg({ name: 'backup', title: 'Backup', category: 'storage', description: 'Kopia.' }),
  pkg({ name: 'jellyfin', title: 'Media server (Jellyfin)', category: 'media', description: 'Jellyfin.' }),
];

const PLAN: InstallPlan = {
  packagesToEnable: [],
  packagesToDisable: [],
  vhosts: [],
  ports: [],
  warnings: [],
};

async function mountPackages() {
  const pinia = createPinia();
  setActivePinia(pinia);
  const store = useOnboardingStore();
  store.selectedPackages = ['core', 'identity', 'storage'];

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/onboarding/packages', component: OnboardingPackages },
      { path: '/onboarding/secrets', component: { template: '<div>secrets</div>' } },
      { path: '/onboarding/domain', component: { template: '<div>domain</div>' } },
    ],
  });
  await router.push('/onboarding/packages');
  await router.isReady();

  const w = mount(OnboardingPackages, { global: { plugins: [pinia, router] } });
  await flushPromises();
  return { w, store, router };
}

function tabLabels(w: ReturnType<typeof mount>): string[] {
  return w.findAll('[role="tab"]').map((t) => t.text().replace(/\d+$/, '').trim());
}

beforeEach(() => {
  vi.spyOn(OnboardingApi, 'previewPlan').mockResolvedValue(PLAN);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('OnboardingPackages', () => {
  describe('category tabs', () => {
    beforeEach(() => {
      vi.spyOn(PackagesApi, 'list').mockResolvedValue(CATALOGUE);
    });

    it('groups the mandatory packages onto one Core tab, with no separate Identity/Auth tab', async () => {
      const { w } = await mountPackages();
      const labels = tabLabels(w);
      expect(labels).toContain('Core');
      expect(labels).not.toContain('Identity');
      expect(labels).not.toContain('Auth');
    });

    it('keeps a non-mandatory category tab alive when it still has a non-mandatory member', async () => {
      const { w } = await mountPackages();
      // 'backup' is category 'storage' but not mandatory, so Storage
      // survives as its own tab even though the mandatory 'storage'
      // package (Samba) has moved to Core.
      expect(tabLabels(w)).toContain('Storage');
    });

    it('shows every mandatory package under the Core tab', async () => {
      const { w } = await mountPackages();
      const coreTab = w.findAll('[role="tab"]').find((t) => t.text().startsWith('Core'));
      await coreTab?.trigger('click');
      await flushPromises();
      expect(w.find('[data-package="core"]').exists()).toBe(true);
      expect(w.find('[data-package="identity"]').exists()).toBe(true);
      expect(w.find('[data-package="storage"]').exists()).toBe(true);
      expect(w.find('[data-package="privacy"]').exists()).toBe(false);
    });

    it('never renders a tab with nothing in it', async () => {
      const { w } = await mountPackages();
      for (const tab of w.findAll('[role="tab"]')) {
        await tab.trigger('click');
        await flushPromises();
        expect(w.findAll('[data-package]').length).toBeGreaterThan(0);
      }
    });

    it('cannot filter identity out of every tab — it always renders somewhere', async () => {
      const { w } = await mountPackages();
      let seenIdentity = false;
      for (const tab of w.findAll('[role="tab"]')) {
        await tab.trigger('click');
        await flushPromises();
        if (w.find('[data-package="identity"]').exists()) seenIdentity = true;
      }
      expect(seenIdentity).toBe(true);
    });

    it('prefers the manifest title over the slug-derived label', async () => {
      const { w } = await mountPackages();
      expect(w.text()).toContain('Media server (Jellyfin)');
      expect(w.text()).not.toContain('Jellyfin</span>');
    });
  });

  describe('when the backend is slow or unreachable', () => {
    it('shows a skeleton, not a hardcoded fallback catalogue, while the fetch is pending', async () => {
      let resolveList!: (v: PackageSummary[]) => void;
      vi.spyOn(PackagesApi, 'list').mockReturnValue(
        new Promise((resolve) => { resolveList = resolve; }),
      );

      const { w } = await mountPackages();
      expect(w.find('[data-slot="skeleton"]').exists()).toBe(true);
      // None of the old hand-written fallback descriptions ever appear.
      expect(w.text()).not.toContain('Caddy reverse proxy.');
      expect(w.find('[data-package]').exists()).toBe(false);

      resolveList(CATALOGUE);
      await flushPromises();
      expect(w.find('[data-slot="skeleton"]').exists()).toBe(false);
      expect(w.find('[data-package="core"]').exists()).toBe(true);
    });

    it('shows an explicit error with a retry instead of stale fallback data', async () => {
      const list = vi.spyOn(PackagesApi, 'list').mockRejectedValue(new Error('network down'));

      const { w } = await mountPackages();
      expect(w.text()).toContain("Couldn't reach the backend");
      expect(w.find('[data-package]').exists()).toBe(false);
      expect(w.text()).not.toContain('Caddy reverse proxy.');

      list.mockResolvedValue(CATALOGUE);
      const retry = w.findAll('button').find((b) => b.text() === 'Try again');
      expect(retry).toBeTruthy();
      await retry?.trigger('click');
      await flushPromises();

      expect(w.find('[data-package="core"]').exists()).toBe(true);
      expect(w.text()).not.toContain("Couldn't reach the backend");
    });
  });
});
