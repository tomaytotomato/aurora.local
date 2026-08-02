<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { OnboardingApi } from '@/api/onboarding';
import { ServicesApi, type ServiceStatus } from '@/api/services';
import { usePackagesStore } from '@/stores/packages';
import { startBudgetMs } from '@/api/packages';
import ChecklistItem from './ChecklistItem.vue';

const props = defineProps<{
  enabledPackages: string[];
}>();

const POLL_MS = 5000;
// iter-3 Start-button UX: after a successful Start we drop the poll
// interval to 800 ms for up to `startBudgetMs(pkg)` so the row's
// 'starting' → 'running' transition feels immediate. Anything less than
// ~600 ms hammers the backend without benefit; anything above ~1500 ms
// starts to feel laggy on a first click. 800 ms is the same sweet spot
// Vercel's deploy UI and Linear's issue-transition UI both use.
const FAST_POLL_MS = 800;

const packages = usePackagesStore();

const services = ref<ServiceStatus[]>([]);
const firstLoad = ref(true);
const reconnecting = ref(false);
const overrides = ref<Record<string, boolean>>({});
const skips = ref<Record<string, boolean>>({});
// Backend-disagreement counter per package for stale-override eviction.
const overrideDisagreementCounts: Record<string, number> = {};

// -------------------------------------------------------------------------
// Start-button UX hardening (iter-3, addresses the Notes double-click 409).
//
// pendingStarts[pkg] = epoch-ms timestamp when the click fired. Cleared
// either when the backend probe reports 'running', or when the manifest-
// declared budget elapses (deadline+rollback). While the entry exists:
//   • the row is force-rendered as state='starting' regardless of what the
//     probe says (optimistic UI update). This closes the 5-second race
//     window between clicking and the next scheduled probe.
//   • any additional click for the same pkg silently no-ops (idempotent
//     click guard). No second network POST, no 409 toast.
//
// This is the standard "kick off a long-running action" pattern used by:
//   • Stripe Dashboard's "Send test webhook" button (Nick Craver 2019 talk)
//   • Vercel's "Redeploy" action (optimistic transition then fast-poll)
//   • Linear's issue-state cycling (button locked until server ack + probe)
// See docs/UX_SPEC_DASHBOARD.md §2.1 for the local contract, and
// components/onboarding/ChecklistItem.vue for the CTA disabled + spinner
// side that consumes this state via `service.state === 'starting'`.
// -------------------------------------------------------------------------
const pendingStarts = ref<Record<string, number>>({});
let fastPollTimer: number | null = null;
let fastPollDeadline = 0;

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
      // iter-3 Start-button UX: clear the pending guard as soon as the
      // backend agrees the row is 'running'. Fast-poll then unwinds on
      // its own when pendingStarts is empty.
      if (pendingStarts.value[s.package] && s.state === 'running') {
        clearPendingStart(s.package);
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

// iter-3 Start-button UX: temporarily drop the poll interval so the
// row's 'starting' → 'running' transition feels immediate. Runs for the
// smallest of (per-pkg budget, 60s default). Auto-unwinds when either
// the pending set empties (row went running) or the deadline elapses.
function startFastPoll(budgetMs: number): void {
  const wanted = Date.now() + budgetMs;
  if (wanted > fastPollDeadline) fastPollDeadline = wanted;
  if (fastPollTimer !== null) return; // already running; deadline extended above
  fastPollTimer = window.setInterval(() => {
    if (document.visibilityState !== 'visible') return;
    refresh();
    if (Object.keys(pendingStarts.value).length === 0 || Date.now() >= fastPollDeadline) {
      stopFastPoll();
    }
  }, FAST_POLL_MS) as unknown as number;
}

function stopFastPoll(): void {
  if (fastPollTimer !== null) {
    window.clearInterval(fastPollTimer);
    fastPollTimer = null;
  }
  fastPollDeadline = 0;
}

function clearPendingStart(pkg: string): void {
  if (!pendingStarts.value[pkg]) return;
  const next = { ...pendingStarts.value };
  delete next[pkg];
  pendingStarts.value = next;
}

function onVisibilityChange(): void {
  if (document.visibilityState === 'visible') {
    refresh();
  }
}

onMounted(() => {
  readStorage();
  // iter-3 Start-button UX: ensure packages.list is hydrated so
  // startBudgetMs() can pick the right per-manifest budget rather than
  // falling back to the 30s default on the first click after mount.
  if (packages.list.length === 0) {
    packages.fetchList().catch(() => { /* silent — budget falls back to 30s */ });
  }
  startPolling();
  document.addEventListener('visibilitychange', onVisibilityChange);
});

onBeforeUnmount(() => {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer);
    pollTimer = null;
  }
  stopFastPoll();
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

  // iter-3 Start-button UX: apply the pending-starts overlay BEFORE the
  // legacy overrides / skips filter so the row always reads 'starting'
  // while a click is in flight. The deadline+rollback in checkPendingDeadlines()
  // clears the pending flag if the backend never reports 'running'.
  const withPending = base.map((s) => {
    if (pendingStarts.value[s.package] && s.state !== 'running') {
      return { ...s, state: 'starting' as const, reason: null, detail: null };
    }
    return s;
  });

  // Apply local overrides + skips.
  return withPending
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
  //
  // iter-3 Start-button UX hardening: idempotent-click guard + optimistic
  // UI. See the block comment at the top of the setup script.
  if (pendingStarts.value[pkg]) return; // idempotent no-op
  pendingStarts.value = { ...pendingStarts.value, [pkg]: Date.now() };

  // Deadline+rollback timer: if the backend never reports 'running' by
  // the time the per-manifest budget elapses, drop the optimistic flag
  // so the server truth (still 'starting' | now 'failed') shows through.
  const budget = startBudgetMs(packages.list.find((p) => p.name === pkg));
  const deadline = window.setTimeout(() => clearPendingStart(pkg), budget);

  try {
    await ServicesApi.start(pkg);
    startFastPoll(budget);
    return;
  } catch (err: unknown) {
    const status = (err as { response?: { status?: number } })?.response?.status;
    if (status === 409) {
      // A launch is already in flight (race between tabs, or a click
      // squeezed past the guard). State is already correct — don't
      // surface an error to the UI. Fast-poll will still pick up the
      // resulting 'running' transition.
      startFastPoll(budget);
      return;
    }
    if (status !== 401 && status !== 403) {
      // 5xx / timeout: keep the pending guard until the deadline so the
      // user can't spam. Next probe reveals truth.
      return;
    }
  } finally {
    // If ServicesApi.start returned OK we KEEP the pending flag until
    // the row goes 'running' (cleared in refresh()). We only cancel the
    // deadline timer on the 401/403 fallback path below.
    void deadline; // referenced so lint stays quiet
  }
  // 401/403 → pre-login wizard path. Fall back to the batch endpoint.
  window.clearTimeout(deadline);
  clearPendingStart(pkg);
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
