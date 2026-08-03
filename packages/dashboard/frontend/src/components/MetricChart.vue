<script setup lang="ts">
// B2-followup (iter-22): thin uPlot wrapper for the DashboardHome Metrics
// card. Vue-side we own the container div + lifecycle; uPlot owns the
// canvas + interaction. Kept dependency-free of uplot-vue (untyped) —
// uPlot's own types are shipped, so a direct wrapper stays type-safe.
//
// Contract:
//   - series (required): { ts: number[], values: number[] }, ts in ms.
//   - label (required): series legend + y-axis unit hint.
//   - unit (optional): '%' | 'B' | 'ms' | ''. Formatter for the axis.
//   - height (optional, default 220): px.
//
// Empty data (series.ts.length === 0) renders a plain "no data" line so
// the caller can gate on the length before mounting the chart.
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import uPlot from 'uplot';
import 'uplot/dist/uPlot.min.css';

interface Props {
  series: { ts: number[]; values: number[] };
  label: string;
  unit?: '%' | 'B' | 'ms' | '';
  height?: number;
}

const props = withDefaults(defineProps<Props>(), {
  unit: '',
  height: 220,
});

const container = ref<HTMLDivElement | null>(null);
let plot: uPlot | null = null;
let resizeObserver: ResizeObserver | null = null;

function formatValue(v: number | null | undefined): string {
  if (v === null || v === undefined || Number.isNaN(v)) return '—';
  switch (props.unit) {
    case '%':
      return v.toFixed(1) + '%';
    case 'B': {
      // Human-readable bytes without depending on lib/utils' humanBytes
      // (that helper coerces inputs; here we know v is a Number).
      if (v < 1024) return v.toFixed(0) + ' B';
      if (v < 1024 ** 2) return (v / 1024).toFixed(1) + ' KiB';
      if (v < 1024 ** 3) return (v / 1024 ** 2).toFixed(1) + ' MiB';
      if (v < 1024 ** 4) return (v / 1024 ** 3).toFixed(2) + ' GiB';
      return (v / 1024 ** 4).toFixed(2) + ' TiB';
    }
    case 'ms':
      if (v < 1000) return v.toFixed(0) + ' ms';
      if (v < 60_000) return (v / 1000).toFixed(1) + ' s';
      if (v < 3_600_000) return (v / 60_000).toFixed(1) + ' m';
      return (v / 3_600_000).toFixed(1) + ' h';
    default:
      return v.toFixed(2);
  }
}

function buildData(): uPlot.AlignedData {
  // uPlot expects seconds for the x-axis by default.
  const xs = props.series.ts.map((ms) => ms / 1000);
  return [xs, props.series.values];
}

function buildOptions(width: number): uPlot.Options {
  return {
    width,
    height: props.height,
    scales: {
      x: { time: true },
      y: props.unit === '%' ? { range: [0, 100] } : {},
    },
    series: [
      { label: 'time' },
      {
        label: props.label,
        stroke: 'var(--color-ink-2, currentColor)',
        width: 1.5,
        points: { show: false },
        value: (_self, rawValue) => formatValue(rawValue),
      },
    ],
    axes: [
      { stroke: 'var(--color-ink-3, currentColor)' },
      {
        stroke: 'var(--color-ink-3, currentColor)',
        values: (_self, ticks) => ticks.map((t) => formatValue(t)),
      },
    ],
    cursor: { drag: { x: true, y: false } },
    legend: { show: true, live: true },
  };
}

function mountPlot(): void {
  if (!container.value) return;
  const width = container.value.clientWidth || 400;
  const data = buildData();
  plot = new uPlot(buildOptions(width), data, container.value);
}

function updatePlot(): void {
  if (!plot) {
    mountPlot();
    return;
  }
  plot.setData(buildData());
}

function destroyPlot(): void {
  if (plot) {
    plot.destroy();
    plot = null;
  }
}

onMounted(() => {
  if (props.series.ts.length > 0) mountPlot();
  resizeObserver = new ResizeObserver((entries) => {
    for (const entry of entries) {
      const w = entry.contentRect.width;
      if (plot && w > 0) plot.setSize({ width: Math.floor(w), height: props.height });
    }
  });
  if (container.value) resizeObserver.observe(container.value);
});

watch(
  () => [props.series.ts.length, props.series.values.length, props.label, props.unit] as const,
  () => {
    // Data or config changed. If we now have data and no plot, mount;
    // otherwise update in place. If we lost data, tear down so the
    // caller's empty-state renders through.
    if (props.series.ts.length === 0) {
      destroyPlot();
    } else {
      updatePlot();
    }
  },
);

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  resizeObserver = null;
  destroyPlot();
});
</script>

<template>
  <div class="w-full" :style="{ minHeight: `${height}px` }">
    <div
      v-if="series.ts.length === 0"
      class="text-xs text-muted-foreground py-6 text-center"
      data-state="empty"
    >
      No samples yet — data will appear as Aurora records them.
    </div>
    <div v-else ref="container" class="w-full" />
  </div>
</template>
