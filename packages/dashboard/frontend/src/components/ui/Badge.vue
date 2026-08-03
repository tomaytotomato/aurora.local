<script setup lang="ts">
import type { HTMLAttributes } from 'vue';
import { computed } from 'vue';
import { cn } from '@/lib/utils';
import { badgeVariants, type BadgeVariants } from './badgeVariants';

// shadcn-vue Badge (C4 iter-6).
// Aurora keeps the `tone` prop (ok/warn/err/info/neutral) instead of
// shadcn's `variant` — see badgeVariants.ts for the migration rationale.
// Coloured tones render a small currentColor dot so they read as a
// status pill even before the label is parsed.
const props = defineProps<{
  tone?: BadgeVariants['tone'];
  class?: HTMLAttributes['class'];
}>();

const tone = computed(() => props.tone ?? 'neutral');

const cls = computed(() =>
  cn(badgeVariants({ tone: tone.value }), props.class),
);

const showDot = computed(() => tone.value !== 'neutral');
</script>

<template>
  <span :class="cls" role="status">
    <span
      v-if="showDot"
      class="inline-block w-1.5 h-1.5 rounded-full bg-current"
      aria-hidden="true"
    />
    <slot />
  </span>
</template>
