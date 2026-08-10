<script setup lang="ts">
import { useSystemStore } from '@/stores/system';
import { computed, onMounted, ref } from 'vue';
import { usePackagesStore } from '@/stores/packages';
import { dockerStructureFor, splitByCore } from '@/api/packages';
import { packageLabel } from '@/lib/packageName';
import Card from '@/components/ui/Card.vue';
import Badge from '@/components/ui/Badge.vue';
import Button from '@/components/ui/Button.vue';
import Skeleton from '@/components/ui/Skeleton.vue';
import DockerBadge from '@/components/DockerBadge.vue';
import SectionNav from '@/components/layout/SectionNav.vue';

const packages = usePackagesStore();

const loadError = ref(false);

function load(): void {
  loadError.value = false;
  packages.fetchList().catch(() => { loadError.value = true; });
}

onMounted(load);

// Core (the curated platform set: core/Caddy, identity/Authelia,
// storage/Samba) runs the platform
// everything else depends on. It's a separate page now rather than a
// tab inside Apps, so its "always on, not removable" framing doesn't
// have to share a tab strip with Installed/Marketplace.
const core = computed(() => splitByCore(packages.list).core);

// The "Your own" tab only appears when the backend can actually run a
// custom stack. Ground rule: a page must never be reachable in
// production while it is still a mock.
const system = useSystemStore();
const appsNav = computed(() => {
  const items = [
    { to: '/apps/catalogue', label: 'Apps' },
    { to: '/apps/core', label: 'Core' },
  ];
  if (system.info?.capabilities?.customStacks === true) {
    items.push({ to: '/apps/custom', label: 'Your own' });
  }
  return items;
});
</script>

<template>
  <section>
    <div class="mb-6 on-photo">
      <h1 class="mb-3">Core</h1>
      <p class="max-w-2xl">The essentials aurora needs to run. You can configure them, but not remove them.</p>
    </div>

    <SectionNav
      :items="appsNav"
      class="mb-6"
    />


    <div v-if="packages.loading && !packages.list.length" class="grid grid-cols-3 gap-6">
      <Card v-for="n in 3" :key="`skeleton-${n}`" class="h-full p-8">
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
      <p class="text-sm text-muted-foreground mb-4">Couldn't load core apps.</p>
      <Button size="sm" variant="secondary" @click="load">Try again</Button>
    </Card>

    <Card v-else-if="!core.length" class="py-16 text-sm text-muted-foreground text-center">
      No core apps found.
    </Card>

    <div v-else class="grid grid-cols-3 gap-6">
      <router-link
        v-for="pkg in core"
        :key="pkg.name"
        :to="`/apps/${pkg.name}`"
        class="no-underline block"
      >
        <Card hover class="h-full p-8 flex flex-col">
          <div class="flex items-start justify-between mb-3">
            <div>
              <div class="eyebrow mb-1">{{ pkg.category }}</div>
              <h3 class="card-title text-foreground">{{ packageLabel(pkg) }}</h3>
            </div>
            <Badge tone="info">core</Badge>
          </div>
          <p class="text-sm text-muted-foreground line-clamp-3 mb-4 flex-1">{{ pkg.description }}</p>
          <div class="flex items-center justify-between gap-3">
            <DockerBadge :structure="dockerStructureFor(pkg)" />
            <span class="text-xs text-muted-foreground">View & configure →</span>
          </div>
        </Card>
      </router-link>
    </div>
  </section>
</template>
