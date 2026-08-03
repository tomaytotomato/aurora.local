<script setup lang="ts">
import type { HTMLAttributes } from 'vue';
import { computed } from 'vue';
import { cn } from '@/lib/utils';

const props = defineProps<{ class?: HTMLAttributes['class'] }>();
const cls = computed(() =>
  cn(
    'border-b border-border transition-colors hover:bg-muted/50',
    'data-[state=selected]:bg-muted',
    props.class,
  ),
);
</script>

<template>
  <tr :class="cls" data-slot="table-row">
    <slot />
  </tr>
</template>
