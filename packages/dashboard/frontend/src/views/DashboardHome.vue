<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useSystemStore } from '@/stores/system';
import { usePackagesStore } from '@/stores/packages';
import { useEventsStore } from '@/stores/events';
import Card from '@/components/ui/Card.vue';
import Badge from '@/components/ui/Badge.vue';
import { humanBytes, relTime } from '@/lib/utils';
import type { MetricSample } from '@/api/system';

const system = useSystemStore();
const packages = usePackagesStore();
const events = useEventsStore();

const chartData = ref<[number[], number[], number[], number[]]>([[], [], [], []]);
const metricsErr = ref<string | null>(null);

function buildChartData(samples: MetricSample[]): void {
  const ts: number[] = [];
  const cpu: number[] = [];
  const mem: number[] = [];
  const disk: number[] = [];
  for (const s of samples) {
    ts.push(s.ts);
    cpu.push(s.cpuPct);
    mem.push(s.memPct);
    disk.push(s.diskPct);
  }
  chartData.value = [ts, cpu, mem, disk];
}

onMounted(async () => {
  events.connect();
  await Promise.allSettled([
    system.fetchInfo(),
    system.fetchState(),
    system.fetchMetrics('24h').then(() => buildChartData(system.metrics)).catch((e) => {
      metricsErr.value = e instanceof Error ? e.message : 'Metrics unavailable';
    }),
    packages.fetchList(),
  ]);
});

const runningCount = computed(() => packages.enabled.filter((p) => p.status === 'running').length);
const degradedCount = computed(() => packages.enabled.filter((p) => p.status === 'degraded').length);
const stoppedCount = computed(() => packages.enabled.filter((p) => p.status === 'stopped').length);

const memPct = computed(() => {
  if (!system.info || system.info.memTotalBytes === 0) return 0;
  return Math.round((system.info.memUsedBytes / system.info.memTotalBytes) * 100);
});
const diskPct = computed(() => {
  if (!system.info || system.info.diskTotalBytes === 0) return 0;
  return Math.round((system.info.diskUsedBytes / system.info.diskTotalBytes) * 100);
});

const recentEvents = computed(() =>
  [...events.buffer].reverse().slice(0, 5),
);
</script>

<template>
  <section>
    <div class="mb-10">
      <div class="eyebrow mb-2">Overview</div>
      <h1 class="mb-2">
        <span v-if="system.info">{{ system.info.hostname }}.{{ system.info.domain }}</span>
        <span v-else>Aurora</span>
      </h1>
      <p class="text-ink-3">
        <span v-if="system.info">{{ system.info.distro }} · {{ system.info.cpuCount }} vCPU · Docker {{ system.info.dockerVersion }}</span>
        <span v-else>Loading system info…</span>
      </p>
    </div>

    <!-- Bento grid: four tiles, asymmetric -->
    <div class="grid grid-cols-6 gap-4 mb-10">
      <Card class="col-span-3 row-span-2">
        <div class="flex items-baseline justify-between mb-1">
          <div class="eyebrow">System</div>
          <span v-if="system.info" class="text-xs text-ink-4 font-mono">
            uptime {{ Math.floor(system.info.uptimeSeconds / 3600) }}h
          </span>
        </div>
        <h3 class="mb-4">Health</h3>
        <div class="space-y-3 text-sm">
          <div class="flex items-center justify-between">
            <span class="text-ink-3">Memory</span>
            <span class="font-mono text-ink" v-if="system.info">
              {{ humanBytes(system.info.memUsedBytes) }} / {{ humanBytes(system.info.memTotalBytes) }}
              <span class="text-ink-4 ml-1">({{ memPct }}%)</span>
            </span>
          </div>
          <div class="flex items-center justify-between">
            <span class="text-ink-3">Disk</span>
            <span class="font-mono text-ink" v-if="system.info">
              {{ humanBytes(system.info.diskUsedBytes) }} / {{ humanBytes(system.info.diskTotalBytes) }}
              <span class="text-ink-4 ml-1">({{ diskPct }}%)</span>
            </span>
          </div>
          <div class="flex items-center justify-between">
            <span class="text-ink-3">Containers</span>
            <span class="font-mono text-ink" v-if="system.info">{{ system.info.containerCount }}</span>
          </div>
        </div>

        <hr class="my-6" />

        <div class="eyebrow mb-3">Recent events</div>
        <div v-if="recentEvents.length === 0" class="text-sm text-ink-4">
          No events yet — waiting on Docker stream.
        </div>
        <ul v-else class="space-y-2 text-xs font-mono">
          <li v-for="e in recentEvents" :key="e.ts + (e.kind === 'docker' ? e.container : '')" class="flex items-center gap-2">
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

      <Card class="col-span-3">
        <div class="eyebrow mb-1">Packages</div>
        <h3 class="mb-4">{{ packages.enabled.length }} enabled</h3>
        <div class="flex items-center gap-3 mb-3">
          <Badge tone="ok">running {{ runningCount }}</Badge>
          <Badge v-if="degradedCount" tone="warn">degraded {{ degradedCount }}</Badge>
          <Badge v-if="stoppedCount" tone="err">stopped {{ stoppedCount }}</Badge>
        </div>
        <router-link to="/packages" class="text-sm text-ink-3">Manage packages →</router-link>
      </Card>

      <Card class="col-span-3">
        <div class="eyebrow mb-1">Security</div>
        <h3 class="mb-2">Posture</h3>
        <p class="text-sm text-ink-3 mb-3">
          Full posture scan lands with the security module.
        </p>
        <router-link to="/security" class="text-sm text-ink-3">Review checks →</router-link>
      </Card>

      <Card class="col-span-6">
        <div class="flex items-baseline justify-between mb-4">
          <div>
            <div class="eyebrow mb-1">Metrics — last 24h</div>
            <h3>CPU, memory, disk</h3>
          </div>
          <div class="text-xs text-ink-4 font-mono">
            <span v-if="chartData[0].length">{{ chartData[0].length }} samples</span>
            <span v-else>no data</span>
          </div>
        </div>
        <div class="h-56 flex items-center justify-center text-sm text-ink-4">
          <template v-if="metricsErr">{{ metricsErr }}</template>
          <template v-else-if="chartData[0].length === 0">
            Metrics stream up after the first sampler tick (~15s after backend boot).
          </template>
          <template v-else>
            <span class="font-mono text-xs">Chart: {{ chartData[0].length }} points across CPU/mem/disk</span>
          </template>
        </div>
      </Card>
    </div>

    <div class="text-xs text-ink-4">
      Aurora is the admin plane. For the tile grid your users see day-to-day, visit
      <a href="/" class="text-ink-3">Homepage</a>.
    </div>
  </section>
</template>
