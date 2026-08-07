<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { usePackagesStore } from '@/stores/packages';
import Card from '@/components/ui/Card.vue';
import Badge from '@/components/ui/Badge.vue';
import Button from '@/components/ui/Button.vue';
import Skeleton from '@/components/ui/Skeleton.vue';
import Tabs from '@/components/ui/Tabs.vue';

const packages = usePackagesStore();

type Filter = 'all' | 'enabled' | 'available';
const activeFilter = ref<Filter>('all');
// A failed load used to be swallowed, so the catalogue rendered "No apps"
// — indistinguishable from a genuinely empty view. Track it so the
// template can offer an honest error state with a retry.
const loadError = ref(false);

function countFor(f: Filter): number {
  if (f === 'enabled') return packages.enabled.length;
  if (f === 'available') return packages.available.length;
  return packages.list.length;
}

// Feed the shared Tabs strip: label + a dim count hint per filter.
const filterTabs = computed(() =>
  (['all', 'enabled', 'available'] as const).map((f) => ({
    value: f,
    label: f.charAt(0).toUpperCase() + f.slice(1),
    hint: String(countFor(f)),
  })),
);

function load(): void {
  loadError.value = false;
  packages.fetchList().catch(() => { loadError.value = true; });
}

onMounted(load);

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
    <div class="mb-8 on-photo">
      <div class="eyebrow mb-2">Catalogue</div>
      <h1 class="mb-3">Apps</h1>
      <p class="max-w-2xl">
        Every app is a small compose stack. Enabling one brings it under the
        <code class="font-mono">aurora</code> compose project and adds its
        vhost to Caddy.
      </p>
    </div>

    <!--
      Shared Tabs, styled `on-photo-tabs` so the triggers read on the
      app-wide photo without an opaque box. Used as a filter strip: no
      default slot, so the filtered grid below renders as normal.
    -->
    <Tabs
      :model-value="activeFilter"
      :tabs="filterTabs"
      class="on-photo-tabs mb-6"
      @update:model-value="activeFilter = $event as Filter"
    />

    <div v-if="packages.loading && !packages.list.length" class="grid grid-cols-3 gap-6">
      <Card v-for="n in 6" :key="`skeleton-${n}`" class="h-full p-8">
        <div class="flex items-start justify-between mb-3">
          <div class="space-y-2">
            <Skeleton class="h-3 w-16" />
            <Skeleton class="h-5 w-28" />
          </div>
          <Skeleton class="h-5 w-14 rounded-full" />
        </div>
        <Skeleton class="h-4 w-full mb-2" />
        <Skeleton class="h-4 w-2/3" />
      </Card>
    </div>

    <Card v-else-if="loadError && !packages.list.length" class="py-16 text-center">
      <p class="text-sm text-muted-foreground mb-4">Couldn't load the catalogue.</p>
      <Button size="sm" variant="secondary" @click="load">Try again</Button>
    </Card>

    <Card v-else-if="!filtered.length" class="py-16 text-sm text-muted-foreground text-center">
      No apps in this view.
    </Card>

    <div v-else class="grid grid-cols-3 gap-6">
      <router-link
        v-for="pkg in filtered"
        :key="pkg.name"
        :to="`/apps/${pkg.name}`"
        class="no-underline block"
      >
        <Card hover class="h-full p-8">
          <div class="flex items-start justify-between mb-3">
            <div>
              <div class="eyebrow mb-1">{{ pkg.category }}</div>
              <h3 class="card-title text-foreground capitalize">{{ pkg.title || pkg.name }}</h3>
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
