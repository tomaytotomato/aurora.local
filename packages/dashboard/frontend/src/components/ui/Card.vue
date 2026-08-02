<script setup lang="ts">
import { cn } from '@/lib/utils';
import { computed } from 'vue';

const props = defineProps<{
  class?: string;
  padded?: boolean;
  hover?: boolean;
}>();

const cls = computed(() =>
  cn(
    // iter-3 theme-flip: explicit text-ink so nested content inside
    // Card doesn't inherit `.on-photo`'s white cascade from a photoBg
    // route. Card is our canonical opaque surface.
    'bg-[var(--color-surface)] text-[var(--color-ink)] border border-[var(--color-line)] rounded-lg',
    // iter-3 padding audit: default card padding 24 → 28 (p-7). Keeps
    // rhythm with DashboardHome's p-8 override without doubling up.
    // Override to zero or larger via `padded: false` + class prop.
    props.padded !== false && 'p-7',
    props.hover && 'transition-colors duration-200 hover:border-[var(--color-ink-4)]',
    props.class,
  ),
);
</script>

<template>
  <div :class="cls">
    <slot />
  </div>
</template>
