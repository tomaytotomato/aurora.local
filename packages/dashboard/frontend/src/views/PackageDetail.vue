<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { usePackagesStore } from '@/stores/packages';
import Card from '@/components/ui/Card.vue';
import Badge from '@/components/ui/Badge.vue';
import Tabs from '@/components/ui/Tabs.vue';
import Alert from '@/components/ui/Alert.vue';

const route = useRoute();
const packages = usePackagesStore();

const err = ref<string | null>(null);
const activeTab = ref<'overview' | 'config' | 'logs' | 'related'>('overview');

const name = computed(() => route.params.name as string);
const detail = computed(() => packages.byName[name.value]);

onMounted(async () => {
  try {
    await packages.fetchOne(name.value);
  } catch (e) {
    err.value = e instanceof Error ? e.message : 'Failed to load package';
  }
});
</script>

<template>
  <section>
    <div class="mb-8">
      <router-link to="/packages" class="text-xs text-ink-3 no-underline">← All packages</router-link>
      <div class="flex items-baseline gap-3 mt-4">
        <h1>{{ name }}</h1>
        <Badge v-if="detail" :tone="detail.enabled ? 'ok' : 'neutral'">
          {{ detail.enabled ? detail.status : 'disabled' }}
        </Badge>
      </div>
      <p v-if="detail" class="text-ink-3 mt-2">{{ detail.description }}</p>
    </div>

    <Alert v-if="err" tone="err" class="mb-6">{{ err }}</Alert>

    <Tabs
      v-model="activeTab"
      :tabs="[
        { value: 'overview', label: 'Overview' },
        { value: 'config', label: 'Config' },
        { value: 'logs', label: 'Logs' },
        { value: 'related', label: 'Related' },
      ]"
    >
      <div v-if="activeTab === 'overview'">
        <div v-if="!detail" class="text-sm text-ink-4">Loading…</div>
        <div v-else class="grid grid-cols-2 gap-4">
          <Card>
            <div class="eyebrow mb-1">Runtime</div>
            <h3 class="mb-3">Containers</h3>
            <div class="text-3xl font-mono text-ink">{{ detail.containers }}</div>
          </Card>
          <Card>
            <div class="eyebrow mb-1">Network</div>
            <h3 class="mb-3">vhosts</h3>
            <ul class="text-sm font-mono text-ink-2 space-y-0.5">
              <li v-for="v in detail.vhosts" :key="v">{{ v }}</li>
              <li v-if="!detail.vhosts.length" class="text-ink-4">none</li>
            </ul>
          </Card>
        </div>
      </div>

      <div v-else-if="activeTab === 'config'">
        <Alert tone="info">Env-form editor lands with M2 (see brief §6.2).</Alert>
      </div>

      <div v-else-if="activeTab === 'logs'">
        <Alert tone="info">Log tail lands with M3.</Alert>
      </div>

      <div v-else>
        <div v-if="detail" class="text-sm text-ink-3">
          <div class="mb-2"><span class="eyebrow">Dependencies:</span></div>
          <div class="flex gap-2 flex-wrap">
            <span v-for="d in detail.dependencies" :key="d" class="font-mono text-xs px-2 py-1 rounded border border-line">{{ d }}</span>
            <span v-if="!detail.dependencies?.length" class="text-ink-4">none</span>
          </div>
        </div>
      </div>
    </Tabs>
  </section>
</template>
