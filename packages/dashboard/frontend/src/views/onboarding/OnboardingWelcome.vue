<script setup lang="ts">
import { onMounted, ref, computed } from 'vue';
import { useRouter } from 'vue-router';
import { useOnboardingStore } from '@/stores/onboarding';
import Button from '@/components/ui/Button.vue';
import { Alert, AlertTitle, AlertDescription } from '@/components/ui';
import { humanBytes } from '@/lib/utils';

const store = useOnboardingStore();
const router = useRouter();

const err = ref<string | null>(null);

onMounted(async () => {
  try {
    if (!store.env) await store.fetchEnv();
  } catch (e) {
    // Public endpoint; a real failure means backend is broken. Surface it.
    err.value = e instanceof Error ? e.message : 'Failed to read system info';
  }
});

function proceed(): void {
  store.next();
  router.push(`/onboarding/${store.currentStep}`);
}

const env = computed(() => store.env);

const notDebian = computed(() => {
  const d = env.value?.distro?.toLowerCase() ?? '';
  return d && !d.includes('debian') && !d.includes('ubuntu');
});

// ---- Resource-card derived values ----------------------------------
// Each `computed` collapses raw env fields into what a card needs to
// render. Everything degrades to '—' when the backing field is null so
// a stale/partial /env payload can't blow up the welcome screen.

const cpuModel = computed(() => env.value?.cpu?.model ?? null);
const cpuLine = computed(() => {
  const c = env.value?.cpu;
  if (!c) return null;
  const parts: string[] = [];
  if (c.cores) parts.push(`${c.cores}c`);
  if (c.threads) parts.push(`${c.threads}t`);
  const cpuMhz = c.mhz;
  const head = parts.length ? parts.join(' / ') : null;
  if (head && cpuMhz) return `${head} @ ${Math.round(cpuMhz)} MHz`;
  if (head) return head;
  if (cpuMhz) return `${Math.round(cpuMhz)} MHz`;
  return null;
});
const cpuLoad = computed(() => {
  const l = env.value?.cpu?.load1;
  return l == null ? null : l.toFixed(2);
});
const gpuMissing = computed(() => env.value?.gpu?.present === false);
const gpuLine = computed(() => {
  const g = env.value?.gpu;
  if (!g?.present) return null;
  const bits = [g.vendor, g.model].filter(Boolean);
  return bits.length ? bits.join(' ') : 'GPU detected';
});

const memTotal = computed(() => {
  const t = env.value?.memory?.MemTotal;
  return t ? humanBytes(t) : null;
});
const memFree = computed(() => {
  const a = env.value?.memory?.MemAvailable ?? env.value?.memory?.MemFree;
  return a ? humanBytes(a) : null;
});

interface DiskRow {
  key: string;
  mount: string;
  total: string;
  usedPct: string | null;
}
const diskRows = computed<DiskRow[]>(() => {
  const disks = env.value?.disks ?? [];
  return disks.map((d, i) => {
    const total = d.total_bytes ? humanBytes(d.total_bytes) : '—';
    let usedPct: string | null = null;
    if (d.total_bytes && d.used_bytes != null) {
      usedPct = `${Math.round((d.used_bytes / d.total_bytes) * 100)}% used`;
    }
    return {
      key: `${d.device}-${d.mount}-${i}`,
      mount: d.mount || '—',
      total,
      usedPct,
    };
  });
});
const diskRowsVisible = computed(() => diskRows.value.slice(0, 4));
const diskRowsExtra = computed(() => Math.max(0, diskRows.value.length - 4));
</script>

<template>
  <div>
    <div class="eyebrow mb-3">Step 1 of 9</div>
    <h1 class="mb-4">Welcome to Aurora.</h1>
    <p class="text-ink-2 text-base leading-relaxed mb-8">
      Aurora is the admin panel for this box. It's opinionated on purpose — most homelab
      setups fail on the same handful of decisions, so we make them for you and get out
      of your way.
    </p>

    <div v-if="err">
      <Alert variant="destructive" class="mb-6">
        <AlertDescription>{{ err }}</AlertDescription>
      </Alert>
    </div>

    <div v-else-if="!env" class="text-sm text-ink-4 mb-8">Reading system info…</div>

    <div v-else class="border border-line rounded-lg mb-6">
      <dl class="divide-y divide-[var(--color-line-2)]">
        <div class="grid grid-cols-3 gap-4 px-5 py-3 text-sm">
          <dt class="text-ink-3">Hostname</dt>
          <dd class="col-span-2 font-mono text-ink">{{ env.hostname ?? '—' }}</dd>
        </div>
        <div class="grid grid-cols-3 gap-4 px-5 py-3 text-sm">
          <dt class="text-ink-3">LAN IP</dt>
          <dd class="col-span-2 font-mono text-ink">{{ env.lanIp ?? '—' }}</dd>
        </div>
        <div class="grid grid-cols-3 gap-4 px-5 py-3 text-sm">
          <dt class="text-ink-3">Distribution</dt>
          <dd class="col-span-2 font-mono text-ink">{{ env.distro ?? '—' }}</dd>
        </div>
        <div class="grid grid-cols-3 gap-4 px-5 py-3 text-sm">
          <dt class="text-ink-3">Kernel</dt>
          <dd class="col-span-2 font-mono text-ink">{{ env.kernel ?? '—' }}</dd>
        </div>
        <div class="grid grid-cols-3 gap-4 px-5 py-3 text-sm">
          <dt class="text-ink-3">Docker</dt>
          <dd class="col-span-2 font-mono text-ink">{{ env.dockerVersion ?? '—' }}</dd>
        </div>
      </dl>
    </div>

    <!--
      Resource snapshot. Three equal cards — CPU / RAM / Disks. Same warm
      off-white surface, single hairline border, no gauges or gradients so
      it reads as continuation of the identity table above, not a
      separate dashboard widget.
    -->
    <div v-if="env" class="grid grid-cols-1 md:grid-cols-3 gap-3 mb-8">
      <!-- CPU -->
      <div class="border border-line rounded-lg px-5 py-4 bg-surface">
        <div class="eyebrow text-ink-3 mb-2">CPU</div>
        <div class="font-serif text-lg leading-snug text-ink truncate" :title="cpuModel ?? undefined">
          {{ cpuModel ?? '—' }}
        </div>
        <div class="font-mono text-xs text-ink-2 mt-1">{{ cpuLine ?? '—' }}</div>
        <div class="font-mono text-xs text-ink-4 mt-0.5">
          load1: {{ cpuLoad ?? '—' }}
        </div>
        <div v-if="gpuMissing" class="text-xs text-ink-4 mt-2 italic">no GPU detected</div>
        <div v-else-if="gpuLine" class="text-xs text-ink-3 mt-2 truncate" :title="gpuLine">
          GPU: {{ gpuLine }}
        </div>
      </div>

      <!-- RAM -->
      <div class="border border-line rounded-lg px-5 py-4 bg-surface">
        <div class="eyebrow text-ink-3 mb-2">RAM</div>
        <div class="font-serif text-lg leading-snug text-ink">{{ memTotal ?? '—' }}</div>
        <div class="font-mono text-xs text-ink-2 mt-1">
          <template v-if="memFree">{{ memFree }} free</template>
          <template v-else>—</template>
        </div>
      </div>

      <!-- Disks -->
      <div class="border border-line rounded-lg px-5 py-4 bg-surface">
        <div class="eyebrow text-ink-3 mb-2">Disks</div>
        <template v-if="diskRows.length > 0">
          <div v-for="row in diskRowsVisible" :key="row.key" class="mb-1.5 last:mb-0">
            <div class="font-mono text-xs text-ink truncate" :title="row.mount">
              <span class="text-ink">{{ row.mount }}</span>
              <span class="text-ink-3"> · {{ row.total }}</span>
            </div>
            <div class="font-mono text-[11px] text-ink-4 leading-tight">
              {{ row.usedPct ?? '—' }}
            </div>
          </div>
          <div v-if="diskRowsExtra > 0" class="text-xs text-ink-4 mt-1">
            +{{ diskRowsExtra }} more
          </div>
        </template>
        <template v-else>
          <div class="font-mono text-xs text-ink-4">—</div>
        </template>
      </div>
    </div>

    <Alert v-if="notDebian" variant="warning" class="mb-8">
      <AlertTitle>Untested distribution</AlertTitle>
      <AlertDescription>
        Aurora is designed for Debian and Ubuntu. Other distros may work, but the host
        Ansible playbooks and firewall roles assume Debian's package layout.
      </AlertDescription>
    </Alert>

    <div class="flex justify-end">
      <Button size="lg" variant="primary" @click="proceed">
        Continue
      </Button>
    </div>
  </div>
</template>
