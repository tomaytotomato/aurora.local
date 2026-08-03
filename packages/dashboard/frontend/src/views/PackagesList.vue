<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { usePackagesStore } from '@/stores/packages';
import Card from '@/components/ui/Card.vue';
import Badge from '@/components/ui/Badge.vue';

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

// iter-3 B4: `toneFor` used to select the badge tone from the ghost
// `.status` field on PackageSummary. The wire never emitted `.status`
// so the Badge now goes off the `.running` boolean directly (see
// template). PackageStatus stays exported from @/api/packages for the
// upcoming BL1 degraded-state work.
</script>

<template>
  <section>
    <div class="mb-8">
      <div class="eyebrow mb-2">Catalogue</div>
      <h1 class="mb-3">Packages</h1>
      <p class="text-muted-foreground max-w-2xl">
        Every package is a small compose stack. Enabling a package brings it under the
        <code class="font-mono text-foreground">aurora</code> compose project and adds its
        vhost to Caddy.
      </p>
    </div>

    <div class="flex items-center gap-1 border-b border-border mb-6">
      <button
        v-for="f in (['all','enabled','available'] as const)"
        :key="f"
        type="button"
        class="px-4 py-2 text-sm capitalize relative"
        :class="activeFilter === f ? 'text-foreground' : 'text-muted-foreground hover:text-foreground'"
        @click="activeFilter = f"
      >
        {{ f }}
        <span
          v-if="activeFilter === f"
          class="absolute inset-x-0 -bottom-px h-px bg-foreground"
        />
      </button>
    </div>

    <div v-if="packages.loading && !packages.list.length" class="text-sm text-muted-foreground">
      Loading catalogue…
    </div>

    <div v-else-if="!filtered.length" class="text-sm text-muted-foreground py-16 text-center">
      No packages in this view.
    </div>

    <div v-else class="grid grid-cols-3 gap-6">
      <router-link
        v-for="pkg in filtered"
        :key="pkg.name"
        :to="`/packages/${pkg.name}`"
        class="no-underline block"
      >
        <Card hover class="h-full p-8">
          <div class="flex items-start justify-between mb-3">
            <div>
              <div class="eyebrow mb-1">{{ pkg.category }}</div>
              <h3 class="text-foreground">{{ pkg.name }}</h3>
            </div>
            <Badge :tone="pkg.enabled ? (pkg.running ? 'ok' : 'neutral') : 'neutral'">
              {{ pkg.enabled ? (pkg.running ? 'running' : 'stopped') : 'off' }}
            </Badge>
          </div>
          <p class="text-sm text-muted-foreground line-clamp-3">{{ pkg.description }}</p>
        </Card>
      </router-link>
    </div>
  </section>
</template>
