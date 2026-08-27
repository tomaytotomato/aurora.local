<script setup lang="ts">
import { useSystemStore } from '@/stores/system';
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { ContainersApi, type ContainerInfo } from '@/api/containers';
import { CORE_SERVICES, type CoreService } from '@/api/core-services';
import Card from '@/components/ui/Card.vue';
import Badge from '@/components/ui/Badge.vue';
import Button from '@/components/ui/Button.vue';
import Skeleton from '@/components/ui/Skeleton.vue';
import AppIcon from '@/components/AppIcon.vue';
import SectionNav from '@/components/layout/SectionNav.vue';

// Core (Caddy, Authelia, Stalwart, Aurora) is the platform every other
// app rides on. Historically this page rendered one card for the whole
// `core` package with a Caddy icon, which read as "only Caddy". That
// misled every operator asking about anything else in the stack.
//
// Now: one card per service, driven by CORE_SERVICES. Live state comes
// from /api/containers (name-matched, since these are single-container
// services with fixed names). Aurora's card is intentionally disabled —
// you cannot open the details of the thing you are currently looking
// at — and points at Settings instead.

const loadError = ref(false);
const containers = ref<Record<string, ContainerInfo | null>>({});
let poll: number | undefined;

async function loadContainers(): Promise<void> {
  // No `?package=` filter: `core` and `dashboard` are two different
  // packages, and asking for both would need two round trips. The
  // unfiltered list is small on this box (a handful of containers) and
  // costs less than the round trips would.
  try {
    const all = await ContainersApi.list();
    const byName: Record<string, ContainerInfo | null> = {};
    for (const svc of CORE_SERVICES) {
      byName[svc.container] = all.find((c) => cleanName(c.names) === svc.container) ?? null;
    }
    containers.value = byName;
    loadError.value = false;
  } catch {
    loadError.value = true;
  }
}

// docker prefixes container names with "/", which is a legacy artifact
// of the old `docker inspect` shape. Every other UI in this app strips
// it, and we should too so the CORE_SERVICES.container comparison works.
function cleanName(names: string[]): string {
  const n = names[0] ?? '';
  return n.startsWith('/') ? n.slice(1) : n;
}

onMounted(() => {
  void loadContainers();
  // 5s poll: cheap on this box (docker ps returns quickly) and matches
  // the cadence /api/services/status is polled at elsewhere. If this
  // ever becomes the SSE story elsewhere, this can switch too.
  poll = window.setInterval(() => void loadContainers(), 5000);
});

onUnmounted(() => {
  if (poll) window.clearInterval(poll);
});

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

const loading = computed(
  () => Object.keys(containers.value).length === 0 && !loadError.value,
);

function stateTone(info: ContainerInfo | null): 'ok' | 'neutral' | 'err' {
  if (!info) return 'neutral';
  if (info.state === 'running') return 'ok';
  if (info.state === 'exited' || info.state === 'dead') return 'err';
  return 'neutral';
}

function stateLabel(svc: CoreService, info: ContainerInfo | null): string {
  if (info) return info.state;
  // No container by that name means the service is not running under
  // this compose project. For `core`-owned services that is a real
  // outage the operator should see plainly; for Aurora it just means
  // the dashboard is running detached and the label is redundant.
  return svc.package === 'dashboard' ? 'you are here' : 'not running';
}
</script>

<template>
  <section>
    <div class="mb-6 on-photo">
      <h1 class="mb-3">Core</h1>
      <p class="max-w-2xl">
        The essentials aurora needs to run. You can configure them, but not remove them.
      </p>
    </div>

    <SectionNav :items="appsNav" class="mb-6" />

    <div
      v-if="loading"
      class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-6"
      data-test="core-loading"
    >
      <Card v-for="n in CORE_SERVICES.length" :key="`skeleton-${n}`" class="h-full p-8">
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

    <Card
      v-else-if="loadError"
      class="py-16 text-center"
      data-test="core-error"
    >
      <p class="text-sm text-muted-foreground mb-4">Couldn't load core services.</p>
      <Button size="sm" variant="secondary" @click="loadContainers">Try again</Button>
    </Card>

    <!-- Same tablet fix as the loading skeleton above: three p-8 cards
         with a description paragraph need two columns from `sm` up. -->
    <div
      v-else
      class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-6"
      data-test="core-grid"
    >
      <template v-for="svc in CORE_SERVICES" :key="svc.key">
        <!--
          Disabled card variant (Aurora). Rendered as a div, not a
          router-link, so it does not offer a click affordance the
          dashboard cannot honour. The hint below the description tells
          the operator where to actually go. Kept visually identical to
          the enabled cards (same padding, same icon layout) so it does
          not read as broken — just non-clickable.
        -->
        <div
          v-if="svc.disabled"
          :data-test="`core-service-${svc.key}`"
          class="opacity-70 cursor-default"
        >
          <Card class="h-full p-8 flex flex-col">
            <div class="flex items-start justify-between mb-3">
              <div class="flex items-start gap-3">
                <AppIcon :src="`/icons/${svc.icon}.svg`" :label="svc.label" />
                <div>
                  <div class="eyebrow mb-1">core service</div>
                  <h3 class="card-title text-foreground">{{ svc.label }}</h3>
                </div>
              </div>
              <Badge tone="neutral">{{ svc.disabled.badge }}</Badge>
            </div>
            <p class="text-sm text-muted-foreground line-clamp-3 mb-4">
              {{ svc.description }}
            </p>
            <div
              class="text-xs text-foreground bg-muted/40 border border-border rounded-md px-3 py-2"
              data-test="core-service-hint"
            >
              {{ svc.disabled.hint }}
              <router-link
                to="/settings"
                class="text-foreground underline ml-1 whitespace-nowrap"
              >Open Settings →</router-link>
            </div>
          </Card>
        </div>

        <router-link
          v-else
          :to="`/apps/core/services/${svc.key}`"
          class="no-underline block"
          :data-test="`core-service-${svc.key}`"
        >
          <Card hover class="h-full p-8 flex flex-col">
            <div class="flex items-start justify-between mb-3">
              <div class="flex items-start gap-3">
                <AppIcon :src="`/icons/${svc.icon}.svg`" :label="svc.label" />
                <div>
                  <div class="eyebrow mb-1">core service</div>
                  <h3 class="card-title text-foreground">{{ svc.label }}</h3>
                </div>
              </div>
              <Badge :tone="stateTone(containers[svc.container] ?? null)">
                {{ stateLabel(svc, containers[svc.container] ?? null) }}
              </Badge>
            </div>
            <p class="text-sm text-muted-foreground line-clamp-3 mb-4 flex-1">
              {{ svc.description }}
            </p>
            <div class="flex items-center justify-between gap-3">
              <span
                v-if="containers[svc.container]"
                class="text-xs text-muted-foreground truncate"
              >{{ containers[svc.container]?.image }}</span>
              <span v-else class="text-xs text-muted-foreground">&nbsp;</span>
              <span class="text-xs text-muted-foreground whitespace-nowrap">
                View details →
              </span>
            </div>
          </Card>
        </router-link>
      </template>
    </div>
  </section>
</template>
