<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { usePackagesStore } from '@/stores/packages';
import { dockerStructureFor, splitByCore, type PackageSummary } from '@/api/packages';
import { packageLabel } from '@/lib/packageName';
import Card from '@/components/ui/Card.vue';
import Badge from '@/components/ui/Badge.vue';
import Button from '@/components/ui/Button.vue';
import Skeleton from '@/components/ui/Skeleton.vue';
import Tabs from '@/components/ui/Tabs.vue';
import DockerBadge from '@/components/DockerBadge.vue';

const packages = usePackagesStore();

// A failed load used to be swallowed, so the catalogue rendered "No apps"
// — indistinguishable from a genuinely empty view. Track it so the
// template can offer an honest error state with a retry.
const loadError = ref(false);

function load(): void {
  loadError.value = false;
  packages.fetchList().catch(() => { loadError.value = true; });
}

onMounted(load);

// ── Apps vs Core ──────────────────────────────────────────────────────
// Core (category === 'core': Caddy + Homepage today) runs the platform
// everything else depends on. It can be configured but not removed, so
// it gets its own tab rather than sitting in the same enable/disable
// grid as everything the owner can actually add or take away.
const split = computed(() => splitByCore(packages.list));

type TopTab = 'apps' | 'core';
const topTab = ref<TopTab>('apps');
const topTabs = computed(() => [
  { value: 'apps' as const, label: 'Apps', hint: String(split.value.apps.length) },
  { value: 'core' as const, label: 'Core', hint: String(split.value.core.length) },
]);

// ── All / Enabled / Available filter, scoped to non-core apps ────────
type Filter = 'all' | 'enabled' | 'available';
const activeFilter = ref<Filter>('all');

function countFor(f: Filter): number {
  const apps = split.value.apps;
  if (f === 'enabled') return apps.filter((p) => p.enabled).length;
  if (f === 'available') return apps.filter((p) => !p.enabled).length;
  return apps.length;
}

const filterTabs = computed(() =>
  (['all', 'enabled', 'available'] as const).map((f) => ({
    value: f,
    label: f.charAt(0).toUpperCase() + f.slice(1),
    hint: String(countFor(f)),
  })),
);

const filteredApps = computed(() => {
  const apps = split.value.apps;
  if (activeFilter.value === 'enabled') return apps.filter((p) => p.enabled);
  if (activeFilter.value === 'available') return apps.filter((p) => !p.enabled);
  return apps;
});

function badgeTone(pkg: PackageSummary): 'ok' | 'neutral' {
  return pkg.enabled && pkg.running ? 'ok' : 'neutral';
}
function badgeText(pkg: PackageSummary): string {
  return pkg.enabled ? (pkg.running ? 'running' : 'stopped') : 'off';
}
</script>

<template>
  <section>
    <div class="mb-6 on-photo">
      <div class="eyebrow mb-2">Catalogue</div>
      <h1 class="mb-3">Apps</h1>
      <p class="max-w-2xl">
        Each app runs in Docker. Enabling one starts it and gives it a web address on
        your network; Core keeps the platform itself running underneath everything else.
      </p>
    </div>

    <!--
      Docker/Compose explainer. Owner-facing surface (unlike onboarding),
      so naming Docker directly is fine. Wrapped in a Card rather than
      a bare Alert — Alert's tint is only a few percent opacity, which
      reads as a faint smudge rather than a banner over the photo (see
      VpnView's same call).
    -->
    <Card class="mb-6 p-5 flex items-start gap-3">
      <DockerBadge structure="compose" class="text-foreground mt-0.5" />
      <p class="text-sm text-muted-foreground">
        Simple apps run in a single Docker container. Apps built from more than one
        service — Sonarr, Radarr and the rest of the *arr stack in Media, for instance —
        run together as a Docker Compose project instead. The badge on each app below
        shows which.
      </p>
    </Card>

    <Tabs
      :model-value="topTab"
      :tabs="topTabs"
      class="on-photo-tabs mb-6"
      @update:model-value="topTab = $event as TopTab"
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

    <!-- APPS -->
    <div v-else-if="topTab === 'apps'">
      <Tabs
        :model-value="activeFilter"
        :tabs="filterTabs"
        size="sm"
        class="on-photo-tabs mb-6"
        @update:model-value="activeFilter = $event as Filter"
      />

      <Card v-if="!filteredApps.length" class="py-16 text-sm text-muted-foreground text-center">
        No apps in this view.
      </Card>

      <div v-else class="grid grid-cols-3 gap-6">
        <router-link
          v-for="pkg in filteredApps"
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
              <Badge :tone="badgeTone(pkg)">{{ badgeText(pkg) }}</Badge>
            </div>
            <p class="text-sm text-muted-foreground line-clamp-3 mb-4 flex-1">{{ pkg.description }}</p>
            <DockerBadge :structure="dockerStructureFor(pkg)" />
          </Card>
        </router-link>
      </div>
    </div>

    <!-- CORE -->
    <div v-else>
      <Card class="mb-6 p-5 text-sm text-muted-foreground">
        Core apps are the essential platform packages aurora needs to run and to support
        every other app — the reverse proxy and the landing dashboard today. They're
        always on: you can view and configure them, but not remove them.
      </Card>

      <Card v-if="!split.core.length" class="py-16 text-sm text-muted-foreground text-center">
        No core apps found.
      </Card>

      <div v-else class="grid grid-cols-3 gap-6">
        <router-link
          v-for="pkg in split.core"
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
            <DockerBadge :structure="dockerStructureFor(pkg)" />
          </Card>
        </router-link>
      </div>
    </div>
  </section>
</template>
