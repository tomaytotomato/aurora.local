<script setup lang="ts">
import { cn } from '@/lib/utils';
import { computed } from 'vue';

const props = defineProps<{
  value: number; // 0..100
  class?: string;
}>();

const pct = computed(() => Math.max(0, Math.min(100, props.value)));
</script>

<template>
  <div :class="cn('h-1 bg-[var(--color-line-2)] rounded-full overflow-hidden', props.class)">
    <div
      class="h-full bg-[var(--color-ink)] transition-all duration-500 ease-out rounded-full"
      :style="{ width: `${pct}%` }"
    />
  </div>
</template>
