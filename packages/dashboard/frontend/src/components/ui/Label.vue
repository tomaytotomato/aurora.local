<script setup lang="ts">
import { cn } from '@/lib/utils';
import { computed } from 'vue';

const props = defineProps<{
  for?: string;
  class?: string;
  hint?: string;
}>();

const cls = computed(() =>
  cn('block text-sm font-medium text-[var(--color-ink-2)] mb-1.5', props.class),
);
</script>

<template>
  <label :for="props.for" :class="cls">
    <slot />
    <span v-if="hint" class="ml-2 text-[var(--color-ink-4)] font-normal text-xs">{{ hint }}</span>
  </label>
</template>
