<script setup lang="ts">
import type { HTMLAttributes } from 'vue';
import { computed } from 'vue';
import { cn } from '@/lib/utils';

// shadcn-vue Progress (C8 iter-10).
//
// Migration is TOKEN-LEVEL, matching shadcn-vue's canonical Progress:
//   track  bg-[var(--color-line-2)] → bg-secondary
//   fill   bg-[var(--color-ink)]    → bg-primary
//
// Added proper ARIA (role=progressbar + aria-valuemin/max/now) so
// assistive tech announces onboarding progress. The old primitive had
// no semantics — a screen reader would just see two empty divs.
const props = defineProps<{
  value: number; // 0..100
  class?: HTMLAttributes['class'];
}>();

const pct = computed(() => Math.max(0, Math.min(100, props.value)));
</script>

<template>
  <div
    :class="cn('h-1 bg-secondary rounded-full overflow-hidden', props.class)"
    role="progressbar"
    :aria-valuemin="0"
    :aria-valuemax="100"
    :aria-valuenow="pct"
  >
    <div
      data-testid="progress-fill"
      class="h-full bg-primary transition-all duration-500 ease-out rounded-full"
      :style="{ width: `${pct}%` }"
    />
  </div>
</template>
