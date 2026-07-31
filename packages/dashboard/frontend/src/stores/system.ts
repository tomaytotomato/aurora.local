import { defineStore } from 'pinia';
import { ref } from 'vue';
import { SystemApi, type SystemInfo, type MetricSample, type StateFile } from '@/api/system';

export const useSystemStore = defineStore('system', () => {
  const info = ref<SystemInfo | null>(null);
  const metrics = ref<MetricSample[]>([]);
  const state = ref<StateFile | null>(null);
  const loading = ref(false);

  async function fetchInfo(): Promise<void> {
    loading.value = true;
    try {
      info.value = await SystemApi.info();
    } finally {
      loading.value = false;
    }
  }

  async function fetchMetrics(window: '1h' | '24h' | '7d' = '24h'): Promise<void> {
    metrics.value = await SystemApi.metrics(window);
  }

  async function fetchState(): Promise<void> {
    state.value = await SystemApi.state();
  }

  return { info, metrics, state, loading, fetchInfo, fetchMetrics, fetchState };
});
