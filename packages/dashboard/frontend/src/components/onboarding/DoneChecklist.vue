<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { OnboardingApi } from '@/api/onboarding';
import { ServicesApi, type ServiceStatus } from '@/api/services';
import ChecklistItem from './ChecklistItem.vue';

const props = defineProps<{
  enabledPackages: string[];
}>();

const POLL_MS = 5000;

const services = ref<ServiceStatus[]>([]);
const firstLoad = ref(true);
const reconnecting = ref(false);
const overrides = ref<Record<string, boolean>>({});
const skips = ref<Record<string, boolean>>({});
// Backend-disagreement counter per package for stale-override eviction.
const overrideDisagreementCounts: Record<string, number> = {};

let pollTimer: number | null = null;

function readStorage(): void {
  try {
    for (const pkg of props.enabledPackages) {
      if (localStorage.getItem(`aurora.checklist.${pkg}.done`) === 'true') {
        overrides.value[pkg] = true;
      }
      if (sessionStorage.getItem(`aurora.checklist.${pkg}.skipped`) === 'true') {
        skips.value[pkg] = true;
      }
    }
  } catch {
    // storage unavailable — ignore
  }
}

async function refresh(): Promise<void> {
  try {
    const res = await ServicesApi.status();
    services.value = res.services;
    reconnecting.value = false;
    firstLoad.value = false;
    // Stale-override reconciliation.
    for (const s of res.services) {
      if (overrides.value[s.package] && s.state !== 'running') {
        overrideDisagreementCounts[s.package] = (overrideDisagreementCounts[s.package] ?? 0) + 1;
        if (overrideDisagreementCounts[s.package] >= 3) {
          overrides.value[s.package] = false;
          try { localStorage.removeItem(`aurora.checklist.${s.package}.done`); } catch { /* noop */ }
        }
      } else {
        overrideDisagreementCounts[s.package] = 0;
      }
      // Unskip anything that flipped to failed.
      if (s.state === 'failed' && skips.value[s.package]) {
        skips.value[s.package] = false;
        try { sessionStorage.removeItem(`aurora.checklist.${s.package}.skipped`); } catch { /* noop */ }
      }
    }
  } catch (err) {
    reconnecting.value = true;
    // Keep previous frame; never blank the list.
  }
}

function startPolling(): void {
  refresh();
  if (pollTimer !== null) window.clearInterval(pollTimer);
  pollTimer = window.setInterval(() => {
    if (document.visibilityState !== 'visible') return;
    refresh();
  }, POLL_MS);
}

function onVisibilityChange(): void {
  if (document.visibilityState === 'visible') {
    refresh();
  }
}

onMounted(() => {
  readStorage();
  startPolling();
  document.addEventListener('visibilitychange', onVisibilityChange);
});

onBeforeUnmount(() => {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer);
    pollTimer = null;
  }
  document.removeEventListener('visibilitychange', onVisibilityChange);
});

// Placeholder rows for skeleton frame while first request is in flight.
const displayServices = computed<ServiceStatus[]>(() => {
  const base = services.value.length > 0
    ? services.value
    : props.enabledPackages.map<ServiceStatus>((pkg) => ({
        package: pkg,
        container: null,
        state: 'starting',
        reason: null,
        detail: null,
        open_url: null,
        priority: 3,
        probed_ms: 0,
      }));

  // Apply local overrides + skips.
  return base
    .filter((s) => !skips.value[s.package])
    .map((s) => {
      if (overrides.value[s.package] && s.state !== 'failed') {
        return { ...s, state: 'running' as const, reason: null, detail: null };
      }
      return s;
    });
});

const showBringingUp = computed(() =>
  displayServices.value.some((s) => s.state === 'failed' || s.state === 'not-started'),
);

function onMarkDone(pkg: string): void {
  overrides.value[pkg] = true;
  overrideDisagreementCounts[pkg] = 0;
  try { localStorage.setItem(`aurora.checklist.${pkg}.done`, 'true'); } catch { /* noop */ }
}

function onSkip(pkg: string): void {
  skips.value[pkg] = true;
  try { sessionStorage.setItem(`aurora.checklist.${pkg}.skipped`, 'true'); } catch { /* noop */ }
}

async function onRetry(pkg: string): Promise<void> {
  // iter-3 dashboard-home fix: DoneChecklist used to unconditionally call
  // OnboardingApi.startLaunch() which POSTs /api/onboarding/launch — that
  // endpoint 409s once onboarding is complete (guarded to the wizard
  // phase). Sarah / Bruce hit it every click on /dashboard/home and saw
  // '409 Conflict: onboarding already complete; use authenticated
  // endpoints'.
  //
  // DoneChecklist has two callers: OnboardingDone.vue (during the wizard,
  // no session cookie yet) and DashboardHome.vue (post-onboarding, session
  // cookie present). Try the authenticated per-package endpoint first;
  // fall back to the wizard-scoped batch launch on 401 (pre-login
  // onboarding path). Any other error is swallowed — next probe reveals
  // the truth.
  try {
    await ServicesApi.start(pkg);
    return;
  } catch (err: unknown) {
    const status = (err as { response?: { status?: number } })?.response?.status;
    if (status !== 401 && status !== 403) return; // 409 / 5xx / timeout — next probe.
  }
  try {
    await OnboardingApi.startLaunch();
  } catch { /* swallow: next probe reveals truth */ }
}

async function onStart(pkg: string): Promise<void> {
  await onRetry(pkg);
}
</script>

<template>
  <div data-checklist="get-started" :class="reconnecting ? 'opacity-70 transition-opacity' : ''">
    <div
      v-if="showBringingUp"
      data-banner="bringing-up"
      class="border border-line rounded-lg p-3 mb-3 bg-[var(--color-surface-2)] text-sm text-ink-2"
    >
      Aurora is still bringing these online. Nothing for you to type.
    </div>
    <ul class="space-y-2 mb-6">
      <ChecklistItem
        v-for="s in displayServices"
        :key="s.package"
        :service="s"
        @mark-done="onMarkDone"
        @skip="onSkip"
        @retry="onRetry"
        @start="onStart"
      />
    </ul>
    <p v-if="reconnecting" class="text-xs text-ink-3">Reconnecting…</p>
  </div>
</template>
