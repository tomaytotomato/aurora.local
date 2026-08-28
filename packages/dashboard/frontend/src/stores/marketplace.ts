import { defineStore } from 'pinia';
import { computed, ref } from 'vue';

import { MarketplaceApi, type MarketplaceApp, type MarketplaceStatus } from '@/api/marketplace';

/**
 * The hosted marketplace catalogue's state, fetched once and shared. The
 * Overview banner ("a new app is available"), the Settings provenance
 * card, and any future marketplace-index-aware surface all read the same
 * status, and the status is a network read behind the scenes.
 *
 * Failures are held, not thrown at consumers: a box that cannot reach the
 * catalogue host should render the last good state, not an error banner on
 * the Overview page. `error` is there for the one surface (Settings) that
 * wants to say so.
 */
export const useMarketplaceStore = defineStore('marketplace', () => {
  const status = ref<MarketplaceStatus | null>(null);
  const apps = ref<MarketplaceApp[]>([]);
  const loading = ref(false);
  const loaded = ref(false);
  const error = ref(false);
  const busy = ref(false);

  /** True when the feature is on and a verified newer catalogue is waiting. */
  const updateAvailable = computed(
    () => status.value?.enabled === true && status.value?.updateAvailable === true,
  );

  async function fetchStatus(): Promise<void> {
    loading.value = true;
    error.value = false;
    try {
      status.value = await MarketplaceApi.status();
      loaded.value = true;
    } catch {
      error.value = true;
    } finally {
      loading.value = false;
    }
  }

  async function fetchApps(): Promise<void> {
    try {
      apps.value = await MarketplaceApi.list();
    } catch {
      // Leave the previous catalogue in place; a stale grid beats an empty one.
    }
  }

  /** Fetch status once per session unless a caller forces it. */
  async function ensureLoaded(): Promise<void> {
    if (loaded.value || loading.value) return;
    await fetchStatus();
  }

  /** Ask the box to pull the remote index now, then reflect the result. */
  async function refresh(): Promise<void> {
    busy.value = true;
    error.value = false;
    try {
      status.value = await MarketplaceApi.refresh();
    } catch {
      error.value = true;
    } finally {
      busy.value = false;
    }
  }

  /** Accept the pending update. Refreshes the app grid on success. */
  async function accept(): Promise<void> {
    busy.value = true;
    error.value = false;
    try {
      status.value = await MarketplaceApi.accept();
      await fetchApps();
    } catch {
      error.value = true;
      throw new Error('accept failed');
    } finally {
      busy.value = false;
    }
  }

  return {
    status,
    apps,
    loading,
    loaded,
    error,
    busy,
    updateAvailable,
    fetchStatus,
    fetchApps,
    ensureLoaded,
    refresh,
    accept,
  };
});
