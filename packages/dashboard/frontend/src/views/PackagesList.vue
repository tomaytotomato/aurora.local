<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { usePackagesStore } from '@/stores/packages';
import Card from '@/components/ui/Card.vue';
import Badge from '@/components/ui/Badge.vue';
import type { PackageStatus } from '@/api/packages';

const packages = usePackagesStore();

const activeFilter = ref<'all' | 'enabled' | 'available'>('all');

onMounted(() => {
  packages.fetchList().catch(() => { /* silent for v0.1 */ });
});

const filtered = computed(() => {
  if (activeFilter.value === 'enabled') return packages.enabled;
  if (activeFilter.value === 'available') return packages.available;
  return packages.list;
});

const toneFor = (s: PackageStatus): 'ok' | 'warn' | 'err' | 'neutral' => {
  if (s === 'running') return 'ok';
  if (s === 'degraded') return 'warn';
  if (s === 'stopped') return 'err';
  return 'neutral';
};
</script>

<template>
  <section>
    <div class="mb-8">
      <div class="eyebrow mb-2">Catalogue</div>
      <h1 class="mb-3">Packages</h1>
      <p class="text-ink-3 max-w-2xl">
        Every package is a small compose stack. Enabling a package brings it under the
        <code class="font-mono text-ink-2">aurora</code> compose project and adds its
        vhost to Caddy.
      </p>
    </div>

    <div class="flex items-center gap-1 border-b border-line mb-6">
      <button
        v-for="f in (['all','enabled','available'] as const)"
        :key="f"
        type="button"
        class="px-4 py-2 text-sm capitalize relative"
        :class="activeFilter === f ? 'text-ink' : 'text-ink-3 hover:text-ink-2'"
        @click="activeFilter = f"
      >
        {{ f }}
        <span
          v-if="activeFilter === f"
          class="absolute inset-x-0 -bottom-px h-px bg-[var(--color-ink)]"
        />
      </button>
    </div>

    <div v-if="packages.loading && !packages.list.length" class="text-sm text-ink-4">
      Loading catalogue…
    </div>

    <div v-else-if="!filtered.length" class="text-sm text-ink-4 py-16 text-center">
      No packages in this view.
    </div>

    <div v-else class="grid grid-cols-3 gap-4">
      <router-link
        v-for="pkg in filtered"
        :key="pkg.name"
        :to="`/packages/${pkg.name}`"
        class="no-underline"
      >
        <Card hover class="h-full">
          <div class="flex items-start justify-between mb-3">
            <div>
              <div class="eyebrow mb-1">{{ pkg.category }}</div>
              <h3 class="text-ink">{{ pkg.name }}</h3>
            </div>
            <Badge :tone="pkg.enabled ? toneFor(pkg.status) : 'neutral'">
              {{ pkg.enabled ? pkg.status : 'off' }}
            </Badge>
          </div>
          <p class="text-sm text-ink-3 line-clamp-3">{{ pkg.description }}</p>
          <div v-if="pkg.enabled && pkg.containers" class="mt-4 text-xs text-ink-4 font-mono">
            {{ pkg.containers }} container{{ pkg.containers === 1 ? '' : 's' }}
          </div>
        </Card>
      </router-link>
    </div>
  </section>
</template>
