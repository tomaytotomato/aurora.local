<script setup lang="ts">
import type { HTMLAttributes } from 'vue';
import { computed } from 'vue';
import { cn } from '@/lib/utils';

const props = defineProps<{ class?: HTMLAttributes['class'] }>();
const cls = computed(() => cn('[&_tr]:border-b [&_tr]:border-border', props.class));
</script>

<template>
  <thead :class="cls" data-slot="table-header">
    <slot />
  </thead>
</template>
