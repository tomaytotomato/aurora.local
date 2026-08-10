import { defineStore } from 'pinia';
import { computed, ref } from 'vue';

import { UpdatesApi, countAvailable, indexByPackage, type PackageUpdate } from '@/api/updates';

/**
 * Update availability, fetched once and shared. The catalogue cards, the
 * app detail page and the Overview attention strip all want the same
 * answer, and asking a registry is not free even server-side.
 *
 * Failures are held rather than thrown at every consumer: an update badge
 * that cannot be computed should be absent, not an error banner. The
 * `error` flag is there for the one surface that wants to say so.
 */
export const useUpdatesStore = defineStore('updates', () => {
  const list = ref<PackageUpdate[]>([]);
  const loading = ref(false);
  const loaded = ref(false);
  const error = ref(false);

  const byPackage = computed(() => indexByPackage(list.value));
  const availableCount = computed(() => countAvailable(list.value));

  async function fetchList(): Promise<void> {
    loading.value = true;
    error.value = false;
    try {
      list.value = await UpdatesApi.list();
      loaded.value = true;
    } catch {
      error.value = true;
    } finally {
      loading.value = false;
    }
  }

  /** Fetch once per session unless a caller forces it. */
  async function ensureLoaded(): Promise<void> {
    if (loaded.value || loading.value) return;
    await fetchList();
  }

  /** Refresh a single row after an update job finishes. */
  async function refreshOne(name: string): Promise<void> {
    try {
      const row = await UpdatesApi.get(name);
      const idx = list.value.findIndex((u) => u.package === name);
      if (idx >= 0) list.value[idx] = row;
      else list.value = [...list.value, row];
    } catch {
      // Leave the previous row in place; a stale badge beats a wrong one.
    }
  }

  return { list, loading, loaded, error, byPackage, availableCount, fetchList, ensureLoaded, refreshOne };
});
