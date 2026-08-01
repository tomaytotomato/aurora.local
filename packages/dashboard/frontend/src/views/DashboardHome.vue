<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useSystemStore } from '@/stores/system';
import { usePackagesStore } from '@/stores/packages';
import { useEventsStore } from '@/stores/events';
import { ServicesApi } from '@/api/services';
import Card from '@/components/ui/Card.vue';
import Button from '@/components/ui/Button.vue';
import Badge from '@/components/ui/Badge.vue';
import { humanBytes, humanUptime, safePercent } from '@/lib/utils';

// iter-dash-1 dashboard-home. Closes the four blockers captured in
// logs/dashboard-bugs-2026-08-01.md and enforces the empty/error state
// contracts from docs/UX_SPEC_DASHBOARD.md §§4-5.
//
// D-rules enforced in this template (search the file for "D<n>"):
//   D1 — no raw axios strings in the DOM.
//   D2 — no NaN in visible copy: safePercent / humanBytes / humanUptime
//        all return "—" for missing inputs.
//   D3 — no `undefined`: hostLabel + every ${…} coerces via helpers.
//   D4 — no 12-hex container ids: hostname sourced from .state.yml only.
//   D5 — no `Back to Homepage` link (killed in TopBar.vue this commit).
//   D8 — every card renders exactly one of {data, empty, error}.

const system = useSystemStore();
const packages = usePackagesStore();
const events = useEventsStore();

// Per-card error banners drive the §5 error-state contract without ever
// exposing an axios error.message to the DOM.
const systemErr = ref(false);
const packagesErr = ref(false);

// Per-package Start button state. Keyed by package name; value is either
// 'starting' (POST in flight) or an error tone code for the row.
type StartState = 'starting' | 'error';
const startState = ref<Record<string, StartState>>({});

async function fetchSystem(): Promise<void> {
  systemErr.value = false;
  try {
    await system.fetchInfo();
  } catch {
    systemErr.value = true;
  }
}

async function fetchPackages(): Promise<void> {
  packagesErr.value = false;
  try {
    await packages.fetchList();
  } catch {
    packagesErr.value = true;
  }
}

onMounted(async () => {
  events.connect();
  await Promise.allSettled([fetchSystem(), fetchPackages()]);
  // iter-1: no metrics fetch. Gated on capabilities.metrics; empty state
  // in the Metrics strip renders unconditionally until the flag flips true.
  // See UX_SPEC_DASHBOARD.md §4.5 + §6.
});

// ---- header + identity ---------------------------------------------
// Never emits ${hostname}.${domain} when either side is missing; a bare
// "aurora.local" fallback keeps the header from reading as broken.
const identity = computed(() => {
  const h = system.info?.hostname ?? null;
  const d = system.info?.domain ?? null;
  if (!h && !d) return 'aurora.local';
  if (!h) return `\u2014.${d}`;
  if (!d) return `${h}.\u2014`;
  return `${h}.${d}`;
});

// ---- system card ---------------------------------------------------
const uptimeText = computed(() => humanUptime(system.info?.uptimeSeconds ?? null));
const memText = computed(() => {
  const u = system.info?.memUsedBytes ?? null;
  const t = system.info?.memTotalBytes ?? null;
  return `${humanBytes(u)} / ${humanBytes(t)}`;
});
const memPct = computed(() => safePercent(
  system.info?.memUsedBytes ?? null,
  system.info?.memTotalBytes ?? null,
));
const diskText = computed(() => {
  const u = system.info?.diskUsedBytes ?? null;
  const t = system.info?.diskTotalBytes ?? null;
  return `${humanBytes(u)} / ${humanBytes(t)}`;
});
const diskPct = computed(() => safePercent(
  system.info?.diskUsedBytes ?? null,
  system.info?.diskTotalBytes ?? null,
));
const containersText = computed(() => {
  const c = system.info?.containerCount;
  return c === null || c === undefined ? '\u2014' : String(c);
});
const distroText = computed(() => system.info?.distro ?? '\u2014');
const cpuText = computed(() => {
  const c = system.info?.cpuCount;
  return c === null || c === undefined ? '\u2014' : String(c);
});
const dockerText = computed(() => system.info?.dockerVersion ?? '\u2014');

// Skeleton-then-empty rule: if system.info is still null 3s after mount
// we show the §4.1 empty state ("Warming up"). Cheap: bind on a boolean
// that flips after 3s, but only when we're still empty.
const warmingUpVisible = ref(false);
setTimeout(() => {
  warmingUpVisible.value = system.info === null && !systemErr.value;
}, 3000);

// ---- packages card + count semantics -------------------------------
// UX_SPEC_DASHBOARD.md §4.3: numerator = probe==='running'; denominator =
// enabled_packages.length. Guard against numerator > denominator.
const runningCount = computed(() => {
  const n = packages.enabled.filter((p) => p.status === 'running').length;
  const d = packages.enabled.length;
  return n > d ? d : n;
});
const packagesCount = computed(() => {
  const n = runningCount.value;
  const d = packages.enabled.length;
  if (d === 0) return { text: '0 enabled', tone: 'neutral' as const };
  if (n === d) return { text: `All ${d} running`, tone: 'ok' as const };
  return { text: `${d} enabled \u00b7 ${n} running`, tone: 'neutral' as const };
});

// Health pill aggregation for the header (UX_SPEC_DASHBOARD.md §3.1).
// Iter-1: simplified — running iff every enabled package is running.
type HealthState = 'running' | 'needs-config' | 'failed' | 'not-started';
const healthState = computed<HealthState>(() => {
  const xs = packages.enabled;
  if (xs.length === 0) return 'not-started';
  if (xs.some((p) => p.status === 'degraded')) return 'failed';
  if (xs.every((p) => p.status === 'running')) return 'running';
  return 'not-started';
});
const healthPill = computed(() => {
  switch (healthState.value) {
    case 'running': return { text: 'Running', tone: 'ok' as const };
    case 'failed':  return { text: 'Attention needed', tone: 'err' as const };
    default:        return { text: 'Not started', tone: 'neutral' as const };
  }
});

// Per-package Start (§2.1 fix). Delegates to POST /api/services/{pkg}/start
// which returns 202 with a job_id. We refresh the package list on both
// success and failure so the pill flips to whatever the probe says next.
async function onStart(name: string): Promise<void> {
  startState.value = { ...startState.value, [name]: 'starting' };
  try {
    await ServicesApi.start(name);
    // Give the launch a beat to register a container before re-listing.
    setTimeout(() => {
      const next = { ...startState.value };
      delete next[name];
      startState.value = next;
      fetchPackages().catch(() => { /* row already in its own state */ });
    }, 1500);
  } catch {
    startState.value = { ...startState.value, [name]: 'error' };
  }
}

// Sort blocker-first for the Packages card — running last, so the user's
// eye lands on the row that needs a Start click.
const enabledSorted = computed(() =>
  [...packages.enabled].sort((a, b) => {
    const rank = (s: string): number => (s === 'running' ? 3 : s === 'degraded' ? 0 : s === 'stopped' ? 1 : 2);
    return rank(a.status) - rank(b.status);
  }),
);

// ---- recent events / containers card -------------------------------
const recentEvents = computed(() => [...events.buffer].reverse().slice(0, 5));
</script>

<template>
  <section>
    <div class="mb-10">
      <div class="eyebrow mb-2">Overview</div>
      <h1 class="mb-2" data-test="identity">{{ identity }}</h1>
      <p class="text-ink-3">
        {{ distroText }} · {{ cpuText }} vCPU · Docker {{ dockerText }}
      </p>
    </div>

    <!-- Bento grid: four tiles, asymmetric -->
    <div class="grid grid-cols-6 gap-4 mb-10">
      <!-- System card -->
      <Card class="col-span-3 row-span-2" data-card="system">
        <div class="flex items-baseline justify-between mb-1">
          <div class="eyebrow">System</div>
          <span class="text-xs text-ink-4 font-mono" data-test="uptime">
            uptime {{ uptimeText }}
          </span>
        </div>
        <h3 class="mb-4">Health</h3>

        <!-- error state (§5) -->
        <div v-if="systemErr" data-state="error" role="alert">
          <p class="text-sm text-ink-2 mb-1">Aurora couldn't read the box's stats.</p>
          <p class="text-sm text-ink-3 mb-3">Refresh the page or try again below.</p>
          <Button variant="secondary" size="sm" @click="fetchSystem">Try again</Button>
        </div>

        <!-- empty state (§4.1) — hydrate window / no data yet -->
        <div v-else-if="!system.info && warmingUpVisible" data-state="empty">
          <p class="text-sm text-ink-2 mb-1">Warming up</p>
          <p class="text-sm text-ink-3">Aurora is taking your box's first measurement.</p>
        </div>

        <!-- hydrated data -->
        <div v-else class="space-y-3 text-sm">
          <div class="flex items-center justify-between">
            <span class="text-ink-3">Memory</span>
            <span class="font-mono text-ink" data-test="memory">
              {{ memText }}
              <span class="text-ink-4 ml-1">
                ({{ memPct === null ? '\u2014' : memPct + '%' }})
              </span>
            </span>
          </div>
          <div class="flex items-center justify-between">
            <span class="text-ink-3">Disk</span>
            <span class="font-mono text-ink" data-test="disk">
              {{ diskText }}
              <span class="text-ink-4 ml-1">
                ({{ diskPct === null ? '\u2014' : diskPct + '%' }})
              </span>
            </span>
          </div>
          <div class="flex items-center justify-between">
            <span class="text-ink-3">Containers</span>
            <span class="font-mono text-ink">{{ containersText }}</span>
          </div>
        </div>

        <hr class="my-6" />

        <div class="eyebrow mb-3">Recent changes</div>
        <!-- §4.2 empty state — must NOT name Docker -->
        <div v-if="recentEvents.length === 0" class="text-sm" data-state="empty">
          <p class="text-ink-2">Nothing has changed recently.</p>
          <p class="text-ink-4 text-xs">Container starts and stops will show up here.</p>
        </div>
        <ul v-else class="space-y-2 text-xs font-mono">
          <li
            v-for="e in recentEvents"
            :key="e.ts + (e.kind === 'docker' ? e.container : '')"
            class="flex items-center gap-2"
          >
            <span class="text-ink-4">{{ new Date(e.ts).toLocaleTimeString() }}</span>
            <span v-if="e.kind === 'docker'">
              <span class="text-ink-2">{{ e.action }}</span>
              <span class="text-ink ml-1">{{ e.container }}</span>
            </span>
            <span v-else-if="e.kind === 'job'" class="text-ink-2">job {{ e.jobId }} · {{ e.phase }}</span>
            <span v-else class="text-ink-2">{{ e.event }}</span>
          </li>
        </ul>
      </Card>

      <!-- Packages card -->
      <Card class="col-span-3" data-card="packages">
        <div class="eyebrow mb-1">Packages</div>
        <h3 class="mb-3" data-test="packages-count">{{ packagesCount.text }}</h3>

        <!-- error state -->
        <div v-if="packagesErr" data-state="error" role="alert">
          <p class="text-sm text-ink-2 mb-1">Aurora couldn't reach the package service.</p>
          <p class="text-sm text-ink-3 mb-3">Refresh the page or try again below.</p>
          <Button variant="secondary" size="sm" @click="fetchPackages">Try again</Button>
        </div>

        <!-- empty state -->
        <div v-else-if="packages.enabled.length === 0" data-state="empty">
          <p class="text-sm text-ink-2 mb-1">You haven't enabled any packages yet.</p>
          <p class="text-sm text-ink-3">
            Add a package from
            <router-link to="/packages" class="text-ink-2 underline">Settings → Packages</router-link>.
          </p>
        </div>

        <!-- hydrated data -->
        <div v-else>
          <div class="flex items-center gap-2 mb-3" data-test="health">
            <Badge :tone="healthPill.tone">{{ healthPill.text }}</Badge>
          </div>
          <ul class="space-y-1.5 mb-3" data-test="package-list">
            <li
              v-for="p in enabledSorted"
              :key="p.name"
              class="flex items-center justify-between text-sm gap-3"
              :data-package="p.name"
              :data-status="p.status"
            >
              <span class="text-ink truncate">{{ p.title || p.name }}</span>
              <div class="flex items-center gap-2 shrink-0">
                <span v-if="p.status === 'running'" class="text-xs text-ink-3">Running</span>
                <span v-else-if="p.status === 'degraded'" class="text-xs text-ink-3">Attention</span>
                <span v-else-if="startState[p.name] === 'starting'" class="text-xs text-ink-3">Starting…</span>
                <span v-else-if="startState[p.name] === 'error'" class="text-xs text-ink-3">Couldn't start</span>
                <Button
                  v-if="p.status !== 'running' && startState[p.name] !== 'starting'"
                  variant="secondary"
                  size="sm"
                  :data-cta="startState[p.name] === 'error' ? 'retry' : 'start'"
                  @click="onStart(p.name)"
                >
                  {{ startState[p.name] === 'error' ? 'Try again' : 'Start' }}
                </Button>
              </div>
            </li>
          </ul>
          <router-link to="/packages" class="text-sm text-ink-3">Manage packages →</router-link>
        </div>
      </Card>

      <!-- Security card (permanent placeholder in iter-1 — §4.4) -->
      <Card class="col-span-3" data-card="security">
        <div class="eyebrow mb-1">Security</div>
        <h3 class="mb-2">Posture</h3>
        <p class="text-sm text-ink-3 mb-3">
          Aurora will start scanning your box for common misconfigurations once the
          security module ships.
        </p>
        <router-link to="/security" class="text-sm text-ink-3">Review checks →</router-link>
      </Card>

      <!-- Metrics strip — §4.5 empty state, no fetch until capability flips -->
      <Card class="col-span-6" data-card="metrics">
        <div class="flex items-baseline justify-between mb-4">
          <div>
            <div class="eyebrow mb-1">Metrics — last 24h</div>
            <h3>CPU, memory, disk</h3>
          </div>
        </div>
        <div class="h-32 flex flex-col items-center justify-center text-sm gap-1" data-state="empty">
          <p class="text-ink-2">Metrics land next release.</p>
          <p class="text-ink-4 text-xs">Aurora will chart your box's last 24 hours here.</p>
        </div>
      </Card>
    </div>
  </section>
</template>
