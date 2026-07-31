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
    'bg-[var(--color-surface)] border border-[var(--color-line)] rounded-lg',
    props.padded !== false && 'p-6',
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
