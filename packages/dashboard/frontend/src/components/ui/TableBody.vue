<script setup lang="ts">
import type { HTMLAttributes } from 'vue';
import { computed } from 'vue';
import { cn } from '@/lib/utils';

const props = defineProps<{ class?: HTMLAttributes['class'] }>();
const cls = computed(() => cn('[&_tr:last-child]:border-0', props.class));
</script>

<template>
  <tbody :class="cls" data-slot="table-body">
    <slot />
  </tbody>
</template>
