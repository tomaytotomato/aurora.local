<script setup lang="ts">
// B3 (v0.3, iter-12): snapshot log-tail viewer for a single container.
// Renders the last N lines returned by GET /api/containers/{id}/logs
// as a <pre>-mono block. No live SSE follow (v0.4 promotion, per
// RALPH_TASK_V02_V03.md B3). Refresh button re-hits the endpoint.
//
// UX contract (DASHBOARD_BRIEF §M3):
//   - Empty state ("No log lines yet.") — fresh container, no output.
//   - Error state — 400 (bad shape) / 404 (no such container) / 5xx.
//   - Truncated banner — surfaced when the backend hit LOG_BYTES_CAP.
//   - Tail selector — 100 / 200 / 500 / 1000 / 2000. Backend rejects
//     anything else, so the picker is authoritative.
//   - Stream tag prefix — stdout/stderr in an inline muted badge; not
//     just a colour so the a11y audit doesn't flag colour-only encoding.
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ContainersApi, type ContainerLogLine } from '@/api/containers';
import { humanCopyForStatus, httpStatusFromError } from '@/lib/http-error-copy';
import Card from '@/components/ui/Card.vue';
import Button from '@/components/ui/Button.vue';
import Alert from '@/components/ui/Alert.vue';
import Badge from '@/components/ui/Badge.vue';

interface RouteError {
  status?: number;
  message: string;
}

const route = useRoute();
const containerId = computed(() => route.params.id as string);

const lines = ref<ContainerLogLine[]>([]);
const truncated = ref(false);
const loading = ref(false);
const err = ref<RouteError | null>(null);
const tail = ref<number>(200);

const TAIL_OPTIONS = [100, 200, 500, 1000, 2000] as const;

async function load(): Promise<void> {
  if (!containerId.value) return;
  loading.value = true;
  err.value = null;
  try {
    const res = await ContainersApi.logs(containerId.value, tail.value);
    lines.value = res.lines;
    truncated.value = res.truncated;
  } catch (e: unknown) {
    const status = httpStatusFromError(e);
    // Human copy per §5 error-state contract — delegates to the
    // shared lib helper so every view speaks the same shape.
    const message = humanCopyForStatus(status, {
      subject: 'the container\u2019s log stream',
      action: 'read',
      badRequest: "Aurora couldn't understand the container name in the URL.",
      notFound: 'That container is not on this box any more.',
    });
    err.value = { status, message };
    lines.value = [];
    truncated.value = false;
  } finally {
    loading.value = false;
  }
}

// Reload when the id in the URL changes (deep navigation), or when the
// user picks a new tail count.
watch(containerId, () => { void load(); });
watch(tail, () => { void load(); });

onMounted(() => { void load(); });

function formatTs(ts: string | undefined): string {
  if (!ts) return '';
  const d = new Date(ts);
  if (Number.isNaN(d.getTime())) return ts.slice(0, 19); // fall back to raw
  return d.toLocaleTimeString(undefined, { hour12: false });
}
</script>

<template>
  <section>
    <div class="mb-6">
      <router-link to="/" class="text-xs text-ink-3 no-underline">← Dashboard</router-link>
      <div class="flex items-baseline justify-between gap-3 mt-4">
        <h1 data-test="logs-container-id">{{ containerId }}</h1>
        <div class="flex items-center gap-2 text-sm">
          <label for="tail" class="text-ink-3">Show</label>
          <select
            id="tail"
            v-model.number="tail"
            class="rounded border border-line bg-surface text-ink px-2 py-1 text-sm"
            data-test="logs-tail-select"
          >
            <option v-for="n in TAIL_OPTIONS" :key="n" :value="n">{{ n }}</option>
          </select>
          <span class="text-ink-4">lines</span>
          <Button variant="secondary" size="sm" :disabled="loading" @click="load"
                  data-test="logs-refresh">
            {{ loading ? 'Refreshing…' : 'Refresh' }}
          </Button>
        </div>
      </div>
      <p class="text-xs text-ink-4 mt-2">
        Snapshot from the container's log stream. Live tail lands in a
        later release.
      </p>
    </div>

    <Alert v-if="err" tone="err" class="mb-6" data-test="logs-error">
      {{ err.message }}
    </Alert>

    <Alert v-if="truncated" tone="warn" class="mb-4" data-test="logs-truncated">
      Log payload hit Aurora's 2 MiB collection cap. Older lines were
      dropped. Reduce the tail count or use <code>docker logs</code> for
      full history.
    </Alert>

    <Card class="p-0" data-card="container-logs">
      <div
        v-if="!err && lines.length === 0 && !loading"
        class="p-8 text-center"
        data-state="empty"
      >
        <p class="text-sm text-ink-2 mb-1">No log lines yet.</p>
        <p class="text-xs text-ink-4">
          This container hasn't written to stdout or stderr in the last
          {{ tail }} lines.
        </p>
      </div>

      <div
        v-else-if="!err && loading && lines.length === 0"
        class="p-8 text-center"
        data-state="empty"
      >
        <p class="text-sm text-ink-2">Loading…</p>
      </div>

      <pre
        v-else
        class="p-4 overflow-x-auto text-xs font-mono leading-relaxed max-h-[70vh]"
        data-test="logs-pre"
      >
<template v-for="(l, i) in lines" :key="i"><span class="text-ink-4 select-none">{{ formatTs(l.ts) }}</span> <Badge :tone="l.stream === 'stderr' ? 'warn' : 'neutral'" class="align-middle">{{ l.stream }}</Badge> <span :class="l.stream === 'stderr' ? 'text-ink' : 'text-ink-2'">{{ l.line }}</span>
</template>
      </pre>
    </Card>
  </section>
</template>
