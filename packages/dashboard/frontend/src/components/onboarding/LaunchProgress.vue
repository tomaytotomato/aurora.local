<script setup lang="ts">
/**
 * Streams a launch job into a compact "bringing services up" UI. Emits
 * `success` or `failed` upward so the parent can toggle the Done page's
 * follow-up affordances (checklist tiles + "Take me to Aurora").
 *
 * The parent owns the POST /onboarding/launch call and passes us the
 * resulting job_id + package list. We manage the EventSource lifecycle,
 * the log tail, and the per-package status pills.
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { OnboardingApi } from '@/api/onboarding';

const props = defineProps<{
  jobId: string;
  packages: string[];
}>();

const emit = defineEmits<{
  (e: 'success'): void;
  (e: 'failed', reason: string): void;
  (e: 'retry'): void;
}>();

type PkgState = 'not-started' | 'starting' | 'running' | 'failed';

// Iter-3 §2a.i: machine-readable failure classification the backend sends
// on `event: done`. Frontend uses it to pick copy — default is Retry.
type FailureCode =
  | 'port_conflict'
  | 'pull_rate_limited'
  | 'disk_full'
  | 'docker_down'
  | 'container_crashed'
  | 'unknown'
  | null;

const logLines = ref<string[]>([
  // Iter-3 §2a.ii: seed the log region so `role="log"` is non-empty at t=0.
  // error-recovery.spec.ts asserts innerText.length > 0 within 3s.
  'Aurora is starting your services…',
]);
const LOG_CAP = 500;

const state = ref<'running' | 'success' | 'failed'>('running');
const failureReason = ref<string | null>(null);
const failureCode = ref<FailureCode>(null);
const startedAt = Date.now();
const elapsedMs = ref(0);
const logOpen = ref(true);
const perPkg = ref<Record<string, PkgState>>(
  Object.fromEntries(props.packages.map((p) => [p, 'not-started'])),
);

let source: EventSource | null = null;
let clock: number | null = null;
// P2 #5: watchdog — if no SSE frame arrives for STALL_MS while the job is
// still marked running, show a reconnecting badge. EventSource auto-
// reconnects transparently for TCP-level drops; this badge is UX cover for
// proxy hangs (nginx idle timeout etc).
const STALL_MS = 30_000;
const lastFrameAt = ref<number>(Date.now());
const now = ref<number>(Date.now());
const stalled = computed(() => state.value === 'running' && (now.value - lastFrameAt.value) > STALL_MS);

// Heuristic package-state extraction from up.sh's line output. Iter-1
// scope: parse the existing `log_step` / compose output rather than
// asking up.sh for structured events.
//
//   - "==> bringing up media" (log_step)         -> media: starting
//   - "Container aurora-media-sonarr Started"    -> media: running (heuristic)
//   - "Container aurora-media-sonarr Healthy"    -> media: running
//   - lines mentioning a package name go through the same match.
function classifyLine(line: string): void {
  const lower = line.toLowerCase();
  for (const p of props.packages) {
    const hit = lower.includes(` ${p} `) || lower.includes(`/${p}`) || lower.includes(`aurora-${p}-`) || lower.includes(`bringing up ${p}`);
    if (!hit) continue;
    if (/(healthy|started|running)/i.test(line) && perPkg.value[p] !== 'failed') {
      perPkg.value[p] = 'running';
    } else if (/(error|failed|exited)/i.test(line)) {
      perPkg.value[p] = 'failed';
    } else if (perPkg.value[p] === 'not-started') {
      perPkg.value[p] = 'starting';
    }
  }
}

function attachStream(id: string): void {
  // Iter-3: keep the initial seeded line rather than blanking the log,
  // so `role="log"` still passes the 3s non-empty assertion on retry.
  logLines.value = ['Aurora is starting your services…'];
  state.value = 'running';
  failureReason.value = null;
  failureCode.value = null;
  for (const p of props.packages) perPkg.value[p] = 'not-started';
  lastFrameAt.value = Date.now();
  now.value = Date.now();

  source?.close();
  source = OnboardingApi.openLaunchStream(id);
  source.addEventListener('log', (ev) => {
    lastFrameAt.value = Date.now();
    const raw = (ev as MessageEvent).data as string;
    let line = raw;
    // The server sends raw strings, but MessageEvent.data is the payload
    // as-is. No JSON envelope in iter-1.
    logLines.value.push(line);
    if (logLines.value.length > LOG_CAP) {
      logLines.value.splice(0, logLines.value.length - LOG_CAP);
    }
    classifyLine(line);
  });
  source.addEventListener('done', (ev) => {
    lastFrameAt.value = Date.now();
    const payload = (ev as MessageEvent).data as string;
    try {
      const parsed = JSON.parse(payload);
      state.value = parsed.state === 'success' ? 'success' : 'failed';
      failureReason.value = parsed.reason ?? (state.value === 'failed'
        ? 'Something went wrong bringing up your services. The log below has the details.'
        : null);
      failureCode.value = (parsed.failure_code ?? null) as FailureCode;
    } catch {
      state.value = 'failed';
      failureReason.value = 'Something went wrong bringing up your services. The log below has the details.';
      failureCode.value = 'unknown';
    }
    // On success, flip any still-pending packages to running (best-effort;
    // heuristic parsing may have missed).
    if (state.value === 'success') {
      for (const p of props.packages) {
        if (perPkg.value[p] !== 'failed') perPkg.value[p] = 'running';
      }
      emit('success');
    } else {
      logOpen.value = true;
      emit('failed', failureReason.value ?? 'unknown failure');
    }
    source?.close();
    source = null;
    if (clock) { window.clearInterval(clock); clock = null; }
  });
  source.addEventListener('ping', () => {
    // heartbeat: no-op, keeps proxies from closing the connection.
    lastFrameAt.value = Date.now();
  });
  source.onerror = () => {
    // Browser will auto-reconnect for transient drops; we only care about
    // hard failures once the stream is closed. If we've already received a
    // `done` event, the source is intentionally closed and this fires once.
  };

  if (clock) window.clearInterval(clock);
  clock = window.setInterval(() => {
    now.value = Date.now();
    elapsedMs.value = Date.now() - startedAt;
  }, 1000);
}

watch(() => props.jobId, (id) => { if (id) attachStream(id); }, { immediate: true });

onBeforeUnmount(() => {
  source?.close();
  if (clock) window.clearInterval(clock);
});

const elapsedLabel = computed(() => {
  const s = Math.floor(elapsedMs.value / 1000);
  const m = Math.floor(s / 60);
  return m > 0 ? `${m}m ${s % 60}s` : `${s}s`;
});

const headerLine = computed(() => {
  if (state.value === 'success') return `All ${props.packages.length} packages running`;
  if (state.value === 'failed') return `Something went wrong bringing up your services`;
  return `Bringing up ${props.packages.length} package${props.packages.length === 1 ? '' : 's'}\u2026`;
});

const headerGlyph = computed(() => {
  if (state.value === 'success') return '\u2713';
  if (state.value === 'failed') return '\u2717';
  return '\u27f3';
});

function pillLabel(s: PkgState): string {
  switch (s) {
    case 'not-started': return 'Queued';
    case 'starting': return 'Starting\u2026';
    case 'running': return 'Running';
    case 'failed': return 'Failed';
  }
}

function retry(): void {
  emit('retry');
}
</script>

<template>
  <div class="border border-border rounded-lg p-5 bg-muted/60" data-testid="launch-progress">
    <div class="flex items-center justify-between mb-4">
      <div class="flex items-center gap-3">
        <span
          class="text-lg"
          :class="{
            'text-[var(--color-accent)] animate-pulse': state === 'running',
            'text-success': state === 'success',
            'text-destructive': state === 'failed',
          }"
        >{{ headerGlyph }}</span>
        <div>
          <div class="font-medium">{{ headerLine }}</div>
          <div class="text-xs text-muted-foreground mt-0.5" v-if="state === 'running'">
            elapsed {{ elapsedLabel }}
          </div>
        </div>
      </div>
      <button
        v-if="state === 'failed'"
        class="text-sm px-3 py-1 rounded border border-border hover:bg-card"
        @click="retry"
        data-testid="launch-retry"
      >Retry</button>
      <span
        v-else-if="stalled"
        class="text-xs px-2 py-0.5 rounded-full border border-border text-muted-foreground bg-card"
        data-testid="launch-reconnecting"
        role="status"
      >Reconnecting…</span>
    </div>

    <div
      v-if="state === 'failed'"
      data-tone="err"
      role="alert"
      class="mb-4 px-4 py-3 rounded border border-destructive/25 bg-destructive/10 text-destructive text-sm"
      data-testid="launch-failure-reason"
    >
      {{ failureReason || 'Something went wrong bringing up your services. The log below has the details.' }}
    </div>

    <ul class="divide-y divide-border border-t border-border" data-testid="launch-package-list">
      <li
        v-for="p in packages"
        :key="p"
        class="flex items-center justify-between py-2 text-sm"
        :data-package="p"
        :data-state="perPkg[p]"
      >
        <span class="font-mono">{{ p }}</span>
        <span
          class="text-xs px-2 py-0.5 rounded-full border"
          :class="{
            'border-border text-muted-foreground bg-card': perPkg[p] === 'not-started',
            'border-[var(--color-accent)] text-[var(--color-accent)] bg-card': perPkg[p] === 'starting',
            'border-success/40 text-success bg-success/10': perPkg[p] === 'running',
            'border-destructive/40 text-destructive bg-destructive/10': perPkg[p] === 'failed',
          }"
        >{{ pillLabel(perPkg[p]) }}</span>
      </li>
    </ul>

    <details :open="logOpen" class="mt-4 group">
      <summary class="text-xs text-muted-foreground cursor-pointer select-none">
        Live log ({{ logLines.length }} line{{ logLines.length === 1 ? '' : 's' }})
      </summary>
      <div
        class="mt-2 bg-foreground text-background font-mono text-xs px-3 py-2 rounded max-h-64 overflow-auto"
        data-testid="launch-log"
        role="log"
        aria-live="polite"
      >
        <div v-for="(line, i) in logLines" :key="i">{{ line }}</div>
      </div>
    </details>

    <div
      v-if="false"
      class="mt-3 text-sm text-destructive"
    ></div>
  </div>
</template>
