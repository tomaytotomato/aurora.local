<script setup lang="ts">
import type { HTMLAttributes } from 'vue';
import { computed } from 'vue';
import { cn } from '@/lib/utils';

// shadcn-vue Label (C5 iter-7).
// Migrated onto shadcn tokens: --color-ink-2 → text-foreground for the
// primary label; --color-ink-4 → text-muted-foreground for the trailing
// hint. Adds peer-disabled affordances to match shadcn convention.
const props = defineProps<{
  for?: string;
  class?: HTMLAttributes['class'];
  hint?: string;
}>();

const cls = computed(() =>
  cn(
    'block text-sm font-medium leading-none text-foreground mb-1.5',
    'peer-disabled:cursor-not-allowed peer-disabled:opacity-70',
    props.class,
  ),
);
</script>

<template>
  <label :for="props.for" :class="cls">
    <slot />
    <span v-if="hint" class="ml-2 text-muted-foreground font-normal text-xs">{{ hint }}</span>
  </label>
</template>
