<script setup lang="ts">
import type { HTMLAttributes } from 'vue';
import { computed } from 'vue';
import { cn } from '@/lib/utils';

const props = defineProps<{ class?: HTMLAttributes['class'] }>();
const cls = computed(() =>
  cn('p-2 align-middle', '[&:has([role=checkbox])]:pr-0', props.class),
);
</script>

<template>
  <td :class="cls" data-slot="table-cell">
    <slot />
  </td>
</template>
