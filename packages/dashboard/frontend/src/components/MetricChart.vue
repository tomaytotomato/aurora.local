<script setup lang="ts">
// Token-driven SVG area chart. Replaces the previous uPlot canvas wrapper.
//
// Why the switch: uPlot draws to a <canvas>, and a canvas 2D context can't
// resolve CSS custom properties — passing `stroke: 'var(--color-…)'` left
// the line black, so the chart was invisible on the dark theme. SVG is
// DOM, so `stroke="var(--color-accent)"` resolves live and the chart
// follows light/dark for free.
//
// Contract is unchanged from the old component:
//   - series (required): { ts: number[], values: number[] }, ts in ms.
//   - label (required): legend label for the hover readout.
//   - unit (optional): '%' | 'B' | 'ms' | '' — value formatter.
//   - height (optional, default 220): px.
// Empty data renders the caller-gated "No samples yet" line.
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';

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

const wrap = ref<HTMLDivElement | null>(null);
const width = ref(600);
let ro: ResizeObserver | null = null;

onMounted(() => {
  if (wrap.value) width.value = wrap.value.clientWidth || 600;
  ro = new ResizeObserver((entries) => {
    for (const e of entries) if (e.contentRect.width > 0) width.value = Math.floor(e.contentRect.width);
  });
  if (wrap.value) ro.observe(wrap.value);
});
onBeforeUnmount(() => {
  ro?.disconnect();
  ro = null;
});

const PAD = { top: 8, right: 8, bottom: 8, left: 8 };

function formatValue(v: number | null | undefined): string {
  if (v === null || v === undefined || Number.isNaN(v)) return '—';
  switch (props.unit) {
    case '%':
      return v.toFixed(1) + '%';
    case 'B': {
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

// Domain. For percentages we pin 0..100 so the scale is stable between
// refreshes; otherwise we fit the data with a little headroom.
const bounds = computed(() => {
  const vs = props.series.values;
  if (props.unit === '%') return { min: 0, max: 100 };
  if (vs.length === 0) return { min: 0, max: 1 };
  let lo = Math.min(...vs);
  let hi = Math.max(...vs);
  if (lo === hi) { lo -= 1; hi += 1; }
  const pad = (hi - lo) * 0.08;
  return { min: lo - pad, max: hi + pad };
});

interface PlotPoint { x: number; y: number; v: number; ts: number }

const points = computed<PlotPoint[]>(() => {
  const { ts, values } = props.series;
  const n = values.length;
  const w = width.value;
  const h = props.height;
  const innerW = Math.max(1, w - PAD.left - PAD.right);
  const innerH = Math.max(1, h - PAD.top - PAD.bottom);
  const { min, max } = bounds.value;
  const span = max - min || 1;
  return values.map((v, i) => ({
    x: PAD.left + (n <= 1 ? innerW / 2 : (i / (n - 1)) * innerW),
    y: PAD.top + (1 - (v - min) / span) * innerH,
    v,
    ts: ts[i] ?? 0,
  }));
});

const linePath = computed(() =>
  points.value.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' '),
);

const areaPath = computed(() => {
  const pts = points.value;
  if (pts.length === 0) return '';
  const base = props.height - PAD.bottom;
  const first = pts[0];
  const last = pts[pts.length - 1];
  return (
    `M${first.x.toFixed(1)},${base.toFixed(1)} ` +
    pts.map((p) => `L${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ') +
    ` L${last.x.toFixed(1)},${base.toFixed(1)} Z`
  );
});

// Three horizontal guide lines at 25/50/75% of the plot height.
const guides = computed(() => {
  const h = props.height;
  const innerH = h - PAD.top - PAD.bottom;
  return [0.25, 0.5, 0.75].map((f) => PAD.top + f * innerH);
});

const gradientId = computed(() => `mc-grad-${props.label.replace(/\W+/g, '')}`);

// Hover readout.
const hover = ref<PlotPoint | null>(null);
function onMove(ev: MouseEvent) {
  const pts = points.value;
  if (pts.length === 0 || !wrap.value) return;
  const rect = wrap.value.getBoundingClientRect();
  const x = ev.clientX - rect.left;
  let nearest = pts[0];
  let best = Infinity;
  for (const p of pts) {
    const d = Math.abs(p.x - x);
    if (d < best) { best = d; nearest = p; }
  }
  hover.value = nearest;
}
function onLeave() { hover.value = null; }

function tsLabel(ts: number): string {
  const d = new Date(ts);
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}
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

    <div v-else ref="wrap" class="relative w-full select-none" :style="{ height: `${height}px` }">
      <svg
        :width="width"
        :height="height"
        class="block"
        role="img"
        :aria-label="`${label} over time`"
        @mousemove="onMove"
        @mouseleave="onLeave"
      >
        <defs>
          <linearGradient :id="gradientId" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="var(--color-accent)" stop-opacity="0.28" />
            <stop offset="100%" stop-color="var(--color-accent)" stop-opacity="0.02" />
          </linearGradient>
        </defs>

        <line
          v-for="(gy, i) in guides"
          :key="i"
          :x1="PAD.left"
          :x2="width - PAD.right"
          :y1="gy"
          :y2="gy"
          stroke="var(--color-border)"
          stroke-width="1"
          stroke-dasharray="2 4"
          opacity="0.7"
        />

        <path :d="areaPath" :fill="`url(#${gradientId})`" />
        <path
          :d="linePath"
          fill="none"
          stroke="var(--color-accent)"
          stroke-width="1.75"
          stroke-linejoin="round"
          stroke-linecap="round"
        />

        <template v-if="hover">
          <line
            :x1="hover.x"
            :x2="hover.x"
            :y1="PAD.top"
            :y2="height - PAD.bottom"
            stroke="var(--color-muted-foreground)"
            stroke-width="1"
            opacity="0.4"
          />
          <circle :cx="hover.x" :cy="hover.y" r="3.5" fill="var(--color-accent)" stroke="var(--color-card)" stroke-width="1.5" />
        </template>
      </svg>

      <!-- Hover readout, positioned in HTML so text never scales with the SVG. -->
      <div
        v-if="hover"
        class="pointer-events-none absolute top-1 rounded-md border border-border bg-card/95 px-2 py-1 text-xs shadow-sm backdrop-blur-sm"
        :style="{ left: Math.min(Math.max(hover.x - 40, 0), width - 96) + 'px' }"
        data-test="metric-chart-tooltip"
      >
        <span class="font-mono text-foreground">{{ formatValue(hover.v) }}</span>
        <span class="text-muted-foreground ml-1">{{ tsLabel(hover.ts) }}</span>
      </div>
    </div>
  </div>
</template>
