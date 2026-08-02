<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { useSystemStore } from '@/stores/system';
import { usePackagesStore } from '@/stores/packages';
import { useEventsStore } from '@/stores/events';
import { ServicesApi } from '@/api/services';
import Card from '@/components/ui/Card.vue';
import Button from '@/components/ui/Button.vue';
import Badge from '@/components/ui/Badge.vue';
import { humanBytes, humanUptime, safePercent } from '@/lib/utils';
import { renderIdentity } from '@/lib/identity';
import { startBudgetMs, type PackageSummary } from '@/api/packages';
import { useHealthPill } from '@/composables/useHealthPill';
import ReachInfo from '@/components/ReachInfo.vue';
import DoneChecklist from '@/components/onboarding/DoneChecklist.vue';

// iter-3 light-mode fix: when photoBg is on, the aurora photo is dark in
// both themes. Outside-card content (page header, section eyebrows) needs
// light text regardless of theme so it reads over the photo. Inside-card
// text is unaffected because Card carries an opaque surface bg.
const route = useRoute();
const photoBg = computed<boolean>(() => Boolean(route.meta?.photoBg));

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
// iter-3 B2: delegate to lib/identity.ts so TopBar and this view share
// the same dedup rule (avoid `aurora.aurora.local` when the hostname is
// already the leading label of the domain). See lib/identity.ts for the
// full rule set.
const identity = computed(() =>
  renderIdentity(system.info?.hostname, system.info?.domain),
);

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
// UX_SPEC_DASHBOARD.md §4.3 + iter-3 B4: numerator = `.running` boolean
// (was `.status === 'running'` — wire never emitted `.status`, so this
// always resolved to 0). Denominator = enabled_packages.length.
const runningCount = computed(() => {
  const n = packages.enabled.filter((p) => p.running).length;
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
// iter-3 V3: lifted into `composables/useHealthPill.ts` so this view and
// TopBar share the same derived state. Degraded transitions land with
// the media sub-checklist (BL1).
const { pill: healthPill } = useHealthPill();

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
    // iter-3 B4: rank on `.running` boolean; degraded state returns
    // with the media sub-checklist (BL1).
    const rank = (p: PackageSummary): number => (p.running ? 3 : 1);
    return rank(a) - rank(b);
  }),
);

// ---- recent events / containers card -------------------------------
const recentEvents = computed(() => [...events.buffer].reverse().slice(0, 5));
</script>

<template>
  <section>
    <div class="mb-10" :class="photoBg && 'on-photo'">
      <div class="eyebrow mb-2">Overview</div>
      <h1 class="mb-2" data-test="identity">{{ identity }}</h1>
      <p :class="photoBg ? 'text-white/80' : 'text-ink-3'">
        {{ distroText }} · {{ cpuText }} vCPU · Docker {{ dockerText }}
      </p>
    </div>

    <!-- Bento grid: four tiles, asymmetric.
         iter-3 B3: gap bumped 4 → 6 and card padding bumped p-6 → p-8 on
         each Card via class prop override. twMerge keeps the later
         padding class. Also loosened row `space-y-3` → `space-y-4` on
         the System hydrated block for more air. -->
    <div class="grid grid-cols-6 gap-6 mb-10">
      <!-- System card.
           iter-dash-polish-2 P3: eyebrow → h3 → subtitle → body anatomy.
           The `uptime` string moved out of the top-right corner into the
           subtitle slot so all four cards share the same slot rhythm. -->
      <Card class="col-span-3 row-span-2 p-8" data-card="system">
        <div class="eyebrow mb-1">System</div>
        <h3 class="mb-1">Resources</h3>
        <p class="text-xs text-ink-4 font-mono mb-4" data-test="uptime">
          uptime {{ uptimeText }}
        </p>

        <!-- iter-3 P1a: reach-info banner. Renders the mDNS host + LAN IP
             so a user watching the dashboard can find both entry points
             without hunting through Settings. Silent when both are null. -->
        <ReachInfo
          v-if="system.info?.domain || system.info?.lanIp"
          :hostname="system.info?.hostname"
          :domain="system.info?.domain"
          :lan-ip="system.info?.lanIp"
          variant="inline"
          class="mb-6 pb-4 border-b border-line/60"
        />

        <!-- error state (§5) -->
        <div v-if="systemErr" data-state="error" role="alert">
          <p class="text-sm text-ink-2 mb-1">Aurora couldn't read the box's stats.</p>
          <p class="text-sm text-ink-3 mb-3">Refresh the page or try again below.</p>
          <Button variant="secondary" size="sm" @click="fetchSystem">Try again</Button>
        </div>

        <!-- empty state (§4.1) — hydrate window / no data yet.
             iter-dash-polish-2 P4: glyph + centred column pattern. -->
        <div
          v-else-if="!system.info && warmingUpVisible"
          data-state="empty"
          class="flex flex-col items-center text-center py-6"
        >
          <svg viewBox="0 0 24 24" class="w-6 h-6 text-ink-4 mb-2" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
            <rect x="3" y="4" width="18" height="7" rx="1" />
            <rect x="3" y="13" width="18" height="7" rx="1" />
            <circle cx="7" cy="7.5" r="0.5" fill="currentColor" />
            <circle cx="7" cy="16.5" r="0.5" fill="currentColor" />
          </svg>
          <p class="text-sm text-ink-2 mb-1">Warming up</p>
          <p class="text-xs text-ink-4">Aurora is taking your box's first measurement.</p>
        </div>

        <!-- hydrated data -->
        <div v-else class="space-y-4 text-sm">
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
        <!-- §4.2 empty state — must NOT name Docker.
             iter-dash-polish-2 P4: glyph + centred column. -->
        <div
          v-if="recentEvents.length === 0"
          class="flex flex-col items-center text-center py-6"
          data-state="empty"
        >
          <svg viewBox="0 0 24 24" class="w-6 h-6 text-ink-4 mb-2" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
            <rect x="3" y="7" width="8" height="5" rx="0.5" />
            <rect x="13" y="7" width="8" height="5" rx="0.5" />
            <rect x="8" y="13" width="8" height="5" rx="0.5" />
          </svg>
          <p class="text-sm text-ink-2">Nothing has changed recently.</p>
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
      <Card class="col-span-3 p-8" data-card="packages">
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
              :data-status="p.running ? 'running' : 'stopped'"
            >
              <span class="text-ink truncate">{{ p.title || p.name }}</span>
              <div class="flex items-center gap-2 shrink-0">
                <span v-if="p.running" class="text-xs text-ink-3">Running</span>
                <span v-else-if="startState[p.name] === 'starting'" class="text-xs text-ink-3">Starting…</span>
                <span v-else-if="startState[p.name] === 'error'" class="text-xs text-ink-3">Couldn't start</span>
                <Button
                  v-if="!p.running && startState[p.name] !== 'starting'"
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

      <!-- Security card (permanent placeholder in iter-1 — §4.4)
           iter-dash-polish-2 P6 (BLOCKER): the `Review checks →` link was
           removed. /security is a stub with hard-coded findings + a
           fabricated score; sending Sarah there manufactures information.
           No CTA per UX_SPEC_DASHBOARD.md §4.4.
           iter-dash-polish-2 P3 + P4: h3 promoted to the empty-state
           headline; body wrapped in the centred glyph pattern so the
           card reads as a designed empty state, not a stub. -->
      <Card class="col-span-3 p-8" data-card="security">
        <div class="eyebrow mb-1">Security</div>
        <h3 class="mb-4">Security posture</h3>
        <div
          data-state="empty"
          class="flex flex-col items-center text-center py-6"
        >
          <svg viewBox="0 0 24 24" class="w-6 h-6 text-ink-4 mb-2" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
            <path d="M12 3 4 6v6c0 4.5 3.4 8.2 8 9 4.6-.8 8-4.5 8-9V6l-8-3Z" />
            <path d="m9 12 2 2 4-4" />
          </svg>
          <p class="text-sm text-ink-2 mb-1">Watching for common misconfigurations</p>
          <p class="text-xs text-ink-4">
            Aurora will start scanning your box once the security module ships.
          </p>
        </div>
      </Card>

      <!-- Metrics strip — §4.5 empty state, no fetch until capability flips.
           iter-dash-polish-2 P5: strip halved in height so it reads as a
           footer, not a broken chart region.
           P3 + P4: eyebrow → h3 (empty-state headline) → subtitle → body,
           with the §4 glyph pattern. -->
      <Card class="col-span-6 p-8" data-card="metrics">
        <div class="eyebrow mb-1">Metrics</div>
        <h3 class="mb-1">Metrics land next release.</h3>
        <p class="text-xs text-ink-4 mb-2">Last 24 hours</p>
        <div
          class="flex items-center justify-center gap-3 text-sm py-2"
          data-state="empty"
        >
          <svg viewBox="0 0 24 24" class="w-5 h-5 text-ink-4 shrink-0" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
            <path d="M3 20V4" />
            <path d="M3 20h18" />
            <path d="m6 15 4-5 4 3 5-7" />
          </svg>
          <p class="text-ink-4 text-xs">Aurora will chart your box's last 24 hours here.</p>
        </div>
      </Card>
    </div>

    <!-- iter-3 BL4: living checklist — the same component the Done page uses
         at the end of onboarding. Sarah's post-onboarding home surface now
         shows real per-package status (polled every 5 s), not just static
         bento cards. Renders only when there are enabled packages. -->
    <section
      v-if="packages.enabled.length > 0"
      class="mt-4"
      :class="photoBg && 'on-photo'"
      data-test="dashboard-done-checklist"
    >
      <div class="eyebrow mb-3">Bring your box online</div>
      <DoneChecklist :enabled-packages="packages.enabled.map((p) => p.name)" />
    </section>
  </section>
</template>
