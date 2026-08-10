<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { usePackagesStore } from '@/stores/packages';
import { useUpdatesStore } from '@/stores/updates';
import { dockerStructureFor, packageLinks, splitCatalogue, type PackageSummary } from '@/api/packages';
import { packageLabel } from '@/lib/packageName';
import Card from '@/components/ui/Card.vue';
import Badge from '@/components/ui/Badge.vue';
import Button from '@/components/ui/Button.vue';
import Skeleton from '@/components/ui/Skeleton.vue';
import Tabs from '@/components/ui/Tabs.vue';
import DockerBadge from '@/components/DockerBadge.vue';
import SectionNav from '@/components/layout/SectionNav.vue';

const packages = usePackagesStore();
const updates = useUpdatesStore();
const router = useRouter();

/** An update badge is a nudge, not a status — absent when we don't know. */
function hasUpdate(pkg: PackageSummary): boolean {
  return updates.byPackage[pkg.name]?.state === 'available';
}

// A failed load used to be swallowed, so the catalogue rendered "No apps"
// — indistinguishable from a genuinely empty view. Track it so the
// template can offer an honest error state with a retry.
const loadError = ref(false);

function load(): void {
  loadError.value = false;
  packages.fetchList().catch(() => { loadError.value = true; });
}

onMounted(() => {
  load();
  // Update state is a separate, slower read (it asks registries). It
  // decorates the cards but never gates them, so it is fired and
  // forgotten — a failure just means no badges.
  void updates.ensureLoaded();
});

// ── Installed / Marketplace — one tab strip, non-core packages only ──
// Core now lives on its own page (PackagesCore.vue), reached via the
// SectionNav above. This replaces the old nested Apps/Core +
// All/Enabled/Available tab strips, which the owner disliked.
const split = computed(() => splitCatalogue(packages.list));

type CatalogueTab = 'installed' | 'marketplace';
const activeTab = ref<CatalogueTab>('installed');
const catalogueTabs = computed(() => [
  { value: 'installed' as const, label: 'Installed', hint: String(split.value.installed.length) },
  { value: 'marketplace' as const, label: 'Marketplace', hint: String(split.value.marketplace.length) },
]);

const visible = computed(() =>
  activeTab.value === 'installed' ? split.value.installed : split.value.marketplace,
);

function badgeTone(pkg: PackageSummary): 'ok' | 'neutral' {
  return pkg.enabled && pkg.running ? 'ok' : 'neutral';
}
function badgeText(pkg: PackageSummary): string {
  return pkg.enabled ? (pkg.running ? 'running' : 'stopped') : 'off';
}

function openDetail(pkg: PackageSummary): void {
  router.push(`/apps/${pkg.name}`);
}
</script>

<template>
  <section>
    <div class="mb-6 on-photo">
      <h1 class="mb-3">Apps</h1>
      <p class="max-w-2xl">Add, configure and remove the apps you want.</p>
    </div>

    <SectionNav
      :items="[{ to: '/apps/catalogue', label: 'Apps' }, { to: '/apps/core', label: 'Core' }, { to: '/apps/custom', label: 'Your own' }]"
      class="mb-6"
    />

    <Tabs
      :model-value="activeTab"
      :tabs="catalogueTabs"
      class="on-photo-tabs mb-6"
      @update:model-value="activeTab = $event as CatalogueTab"
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

    <Card v-else-if="!visible.length" class="py-16 text-sm text-muted-foreground text-center">
      {{ activeTab === 'installed' ? 'Nothing installed yet — check the Marketplace tab.' : 'Nothing left to add — everything is installed.' }}
    </Card>

    <!-- INSTALLED — unchanged whole-card link; no nested interactive
         elements, so the whole Card can be the click target. -->
    <div v-else-if="activeTab === 'installed'" class="grid grid-cols-3 gap-6">
      <router-link
        v-for="pkg in visible"
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
          <div class="flex items-center justify-between gap-3">
            <DockerBadge :structure="dockerStructureFor(pkg)" />
            <Badge
              v-if="hasUpdate(pkg)"
              tone="info"
              data-test="update-available-badge"
            >update</Badge>
          </div>
        </Card>
      </router-link>
    </div>

    <!--
      MARKETPLACE — cards carry real <a> links out to the upstream repo
      and docs, so the card itself can't be a single wrapping <a> (an
      anchor can't nest another anchor). Instead the title is the
      keyboard-accessible RouterLink, the whole Card gets a mouse-
      convenience click-through, and the link row stops that click from
      bubbling so following a link doesn't also navigate to the detail
      page.
    -->
    <div v-else class="grid grid-cols-3 gap-6">
      <Card
        v-for="pkg in visible"
        :key="pkg.name"
        hover
        class="h-full p-8 flex flex-col cursor-pointer"
        @click="openDetail(pkg)"
      >
        <div class="flex items-start justify-between mb-3">
          <div>
            <div class="eyebrow mb-1">{{ pkg.category }}</div>
            <router-link
              :to="`/apps/${pkg.name}`"
              class="card-title text-foreground no-underline hover:underline"
              @click.stop
            >{{ packageLabel(pkg) }}</router-link>
          </div>
          <Badge tone="neutral">available</Badge>
        </div>
        <p class="text-sm text-muted-foreground line-clamp-3 mb-4 flex-1">{{ pkg.description }}</p>
        <div class="flex items-center justify-between gap-3">
          <DockerBadge :structure="dockerStructureFor(pkg)" />
          <div v-if="packageLinks(pkg).length" class="flex items-center gap-3" @click.stop>
            <a
              v-for="link in packageLinks(pkg)"
              :key="link.label"
              :href="link.url"
              target="_blank"
              rel="noopener noreferrer"
              class="text-xs text-muted-foreground no-underline hover:text-foreground hover:underline"
            >{{ link.label }} ↗</a>
          </div>
        </div>
      </Card>
    </div>

    <!--
      Docker/Compose legend — moved off the top of the page (it used to
      be a banner above the fold on every visit) and reformatted as a
      compact footer: the two badge styles side by side, each with a
      one-line definition. Sits on the photo, so colours are set
      explicitly rather than relying on the `.on-photo` cascade.
    -->
    <div class="mt-10 pt-6 border-t border-white/15 flex flex-wrap items-center gap-x-8 gap-y-2">
      <div class="flex items-center gap-2">
        <DockerBadge structure="container" class="text-white/85" />
        <span class="text-xs text-white/60">— one container.</span>
      </div>
      <div class="flex items-center gap-2">
        <DockerBadge structure="compose" class="text-white/85" />
        <span class="text-xs text-white/60">— multiple services running together.</span>
      </div>
    </div>
  </section>
</template>
