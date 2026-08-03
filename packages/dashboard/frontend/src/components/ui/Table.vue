<script setup lang="ts">
import type { HTMLAttributes } from 'vue';
import { computed } from 'vue';
import { cn } from '@/lib/utils';

// shadcn-vue Table (C10 iter-17).
// The wrapper — provides horizontal overflow so wide audit rows don't
// break the layout, plus base typography.
const props = defineProps<{ class?: HTMLAttributes['class'] }>();
const cls = computed(() => cn('w-full caption-bottom text-sm', props.class));
</script>

<template>
  <div class="relative w-full overflow-x-auto" data-slot="table-wrapper">
    <table :class="cls" data-slot="table">
      <slot />
    </table>
  </div>
</template>
