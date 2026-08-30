<script setup lang="ts">
import { computed } from 'vue';
import Badge from '@/components/ui/Badge.vue';
import type { BadgeVariants } from '@/components/ui/badgeVariants';
import type { StatusLightState } from '@/lib/packageLifecycle';

// Status light for the app detail control panel. Built on the existing
// Badge primitive (it already renders a currentColor dot for any
// non-neutral tone) rather than a bespoke dot component, per
// docs/STYLEGUIDE.md's instruction to reuse shadcn-vue primitives.
//
// `unknown` deliberately does NOT reuse `not-installed`'s copy even
// though both render the neutral/grey tone — the label is what makes it
// an honestly distinct state rather than a fabricated green or red guess
// (see deriveStatusLight in lib/packageLifecycle.ts).
const props = defineProps<{
  state: StatusLightState;
}>();

const COPY: Record<StatusLightState, { label: string; tone: BadgeVariants['tone'] }> = {
  running: { label: 'Running', tone: 'ok' },
  stopped: { label: 'Stopped', tone: 'neutral' },
  starting: { label: 'Starting…', tone: 'warn' },
  unhealthy: { label: 'Unhealthy', tone: 'err' },
  'needs-setup': { label: 'Needs setup', tone: 'warn' },
  'not-installed': { label: 'Not installed', tone: 'neutral' },
  unknown: { label: 'Unknown', tone: 'neutral' },
};

const copy = computed(() => COPY[props.state]);
</script>

<template>
  <Badge :tone="copy.tone" :data-status-light="state">{{ copy.label }}</Badge>
</template>
