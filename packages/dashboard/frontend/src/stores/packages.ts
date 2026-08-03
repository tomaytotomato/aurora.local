import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import { PackagesApi, type PackageSummary, type PackageDetail } from '@/api/packages';

export const usePackagesStore = defineStore('packages', () => {
  const list = ref<PackageSummary[]>([]);
  const byName = ref<Record<string, PackageDetail>>({});
  const loading = ref(false);

  const enabled = computed(() => list.value.filter((p) => p.enabled));
  const available = computed(() => list.value.filter((p) => !p.enabled));

  const byCategory = computed(() => {
    const g: Record<string, PackageSummary[]> = {};
    for (const p of list.value) {
      (g[p.category] ||= []).push(p);
    }
    return g;
  });

  async function fetchList(): Promise<void> {
    loading.value = true;
    try {
      list.value = await PackagesApi.list();
    } finally {
      loading.value = false;
    }
  }

  async function fetchOne(name: string): Promise<PackageDetail> {
    const d = await PackagesApi.get(name);
    byName.value[name] = d;
    return d;
  }

  return { list, byName, loading, enabled, available, byCategory, fetchList, fetchOne };
});
