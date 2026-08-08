<script setup lang="ts">
/**
 * Streams one job's log. The shared surface behind every long-running
 * action: adding an app, updating one, starting a stopped package,
 * restoring a snapshot, deploying a custom stack.
 *
 * Why it exists: those actions used to show a spinner on a button and
 * then a toast. On a first pull of Immich or Ollama that is several
 * minutes of no signal at all, which is indistinguishable from a hang.
 *
 * The parent owns the POST that creates the job and passes the id down.
 * We fetch the snapshot once for its metadata (kind, start time), then
 * take every log line from the stream, which replays from the beginning —
 * so reopening the panel on a job that finished an hour ago shows the
 * whole thing and terminates at once.
 *
 * Onboarding's LaunchProgress.vue is the sibling of this component and
 * deliberately stays separate: it shows per-package pills this one has no
 * concept of. Both consume the same `log` / `done` event names.
 */
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';

import {
  JobsApi,
  formatElapsed,
  isTerminal,
  jobElapsedMs,
  type JobState,
  type JobStatus,
} from '@/api/jobs';
import { jobFailureCopy, jobHeadline } from '@/lib/job-copy';
import { humanCopyForError } from '@/lib/http-error-copy';
import { Alert, AlertDescription, Button } from '@/components/ui';

const props = withDefaults(
  defineProps<{
    /** Null renders nothing at all — the parent can leave the panel mounted. */
    jobId: string | null;
    /** Overrides the headline derived from the job's kind. */
    label?: string;
    /** Start with the log expanded. A failure always expands it regardless. */
    logOpen?: boolean;
    /** Show a "Hide" control that emits `dismiss`. */
    dismissible?: boolean;
  }>(),
  { label: undefined, logOpen: false, dismissible: false },
);

const emit = defineEmits<{
  (e: 'success', job: JobStatus): void;
  (e: 'failed', job: JobStatus): void;
  (e: 'retry'): void;
  (e: 'dismiss'): void;
}>();

const LOG_CAP = 1000;
const STALL_MS = 30_000;

const job = ref<JobStatus | null>(null);
const lines = ref<string[]>([]);
const loadError = ref<string | null>(null);
const open = ref(props.logOpen);
const logEl = ref<HTMLElement | null>(null);

// Clock + stall watchdog. EventSource reconnects transparently for TCP
// drops; the badge is cover for a proxy that hangs the connection open
// while sending nothing.
const now = ref(Date.now());
const lastFrameAt = ref(Date.now());
let source: EventSource | null = null;
let clock: number | null = null;

const state = computed<JobState>(() => job.value?.state ?? 'queued');
const running = computed(() => !isTerminal(state.value));
const stalled = computed(() => running.value && now.value - lastFrameAt.value > STALL_MS);

const headline = computed(() => {
  if (props.label) return props.label;
  if (!job.value) return 'Working…';
  return jobHeadline(job.value.kind, job.value.state);
});

const elapsed = computed(() => {
  if (!job.value) return '';
  return formatElapsed(jobElapsedMs(job.value, now.value));
});

const glyph = computed(() => {
  if (state.value === 'success') return '✓';
  if (state.value === 'failed') return '✗';
  return '⟳';
});

const failureCopy = computed(() =>
  job.value && job.value.state === 'failed'
    ? jobFailureCopy(job.value.failureCode, job.value.failureReason)
    : null,
);

const panelState = computed(() => {
  if (loadError.value) return 'error';
  if (!job.value) return 'loading';
  return job.value.state;
});

function stopClock(): void {
  if (clock !== null) {
    window.clearInterval(clock);
    clock = null;
  }
}

function detach(): void {
  source?.close();
  source = null;
  stopClock();
}

function pushLine(line: string): void {
  lines.value.push(line);
  if (lines.value.length > LOG_CAP) {
    lines.value.splice(0, lines.value.length - LOG_CAP);
  }
  if (open.value) void scrollToEnd();
}

async function scrollToEnd(): Promise<void> {
  await nextTick();
  const el = logEl.value;
  if (el) el.scrollTop = el.scrollHeight;
}

async function attach(id: string): Promise<void> {
  detach();
  lines.value = [];
  loadError.value = null;
  job.value = null;
  open.value = props.logOpen;
  lastFrameAt.value = Date.now();
  now.value = Date.now();

  // Snapshot first, for the metadata the headline needs. Its `tail` is
  // deliberately ignored: the stream replays every line from the start,
  // so taking both would double the log.
  try {
    job.value = await JobsApi.get(id);
  } catch (e) {
    loadError.value = humanCopyForError(e, { subject: 'this job', action: 'load' });
    return;
  }

  source = JobsApi.openStream(id);
  source.addEventListener('log', (ev) => {
    lastFrameAt.value = Date.now();
    pushLine((ev as MessageEvent).data as string);
  });
  source.addEventListener('ping', () => {
    lastFrameAt.value = Date.now();
  });
  source.addEventListener('done', (ev) => {
    lastFrameAt.value = Date.now();
    try {
      const parsed = JSON.parse((ev as MessageEvent).data as string) as JobStatus;
      job.value = parsed;
    } catch {
      // A malformed terminal frame still means the job stopped. Mark it
      // failed rather than leaving a spinner turning forever.
      if (job.value) {
        job.value = {
          ...job.value,
          state: 'failed',
          failureCode: 'unknown',
          failureReason: null,
          finishedAt: new Date().toISOString(),
        };
      }
    }
    detach();
    if (!job.value) return;
    if (job.value.state === 'failed') {
      open.value = true;
      void scrollToEnd();
      emit('failed', job.value);
    } else {
      emit('success', job.value);
    }
  });

  clock = window.setInterval(() => {
    now.value = Date.now();
  }, 1000);
}

watch(
  () => props.jobId,
  (id) => {
    if (id) {
      void attach(id);
    } else {
      detach();
      job.value = null;
      lines.value = [];
      loadError.value = null;
    }
  },
  { immediate: true },
);

onBeforeUnmount(detach);
</script>

<template>
  <div
    v-if="jobId"
    class="border border-border rounded-lg p-5 bg-muted/60"
    :data-state="panelState"
    data-test="job-log-panel"
  >
    <div v-if="loadError" role="alert">
      <Alert variant="destructive"><AlertDescription>{{ loadError }}</AlertDescription></Alert>
      <Button size="sm" variant="secondary" class="mt-3" @click="emit('retry')">Try again</Button>
    </div>

    <template v-else>
      <div class="flex items-center justify-between gap-4 mb-3">
        <div class="flex items-center gap-3 min-w-0">
          <span
            class="text-lg shrink-0"
            :class="{
              'text-[var(--color-accent)] animate-pulse': running,
              'text-success': state === 'success',
              'text-destructive': state === 'failed',
            }"
            aria-hidden="true"
          >{{ glyph }}</span>
          <div class="min-w-0">
            <div class="font-medium truncate" data-test="job-headline">{{ headline }}</div>
            <div v-if="elapsed" class="text-xs text-muted-foreground mt-0.5 tabular-nums">
              {{ running ? 'elapsed' : 'took' }} {{ elapsed }}
            </div>
          </div>
        </div>

        <div class="flex items-center gap-2 shrink-0">
          <span
            v-if="stalled"
            class="text-xs px-2 py-0.5 rounded-full border border-border text-muted-foreground bg-card"
            role="status"
            data-test="job-reconnecting"
          >Reconnecting…</span>
          <Button
            v-if="state === 'failed'"
            size="sm"
            variant="secondary"
            data-test="job-retry"
            @click="emit('retry')"
          >Try again</Button>
          <Button
            v-if="dismissible && !running"
            size="sm"
            variant="secondary"
            data-test="job-dismiss"
            @click="emit('dismiss')"
          >Hide</Button>
        </div>
      </div>

      <div
        v-if="failureCopy"
        data-tone="err"
        role="alert"
        class="mb-3 px-4 py-3 rounded border border-destructive/25 bg-destructive/10 text-destructive text-sm"
        data-test="job-failure-reason"
      >
        {{ failureCopy }}
      </div>

      <details :open="open" @toggle="open = ($event.target as HTMLDetailsElement).open">
        <summary class="text-xs text-muted-foreground cursor-pointer select-none">
          Log ({{ lines.length }} line{{ lines.length === 1 ? '' : 's' }})
        </summary>
        <div
          ref="logEl"
          class="mt-2 bg-foreground text-background font-mono text-xs px-3 py-2 rounded max-h-64 overflow-auto"
          data-test="job-log"
          role="log"
          aria-live="polite"
        >
          <div v-for="(line, i) in lines" :key="i">{{ line }}</div>
          <div v-if="!lines.length" class="text-background/60">Waiting for output…</div>
        </div>
      </details>
    </template>
  </div>
</template>
