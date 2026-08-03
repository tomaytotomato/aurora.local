<script setup lang="ts">
import type { HTMLAttributes } from 'vue';
import { computed } from 'vue';
import { cn } from '@/lib/utils';

// shadcn-vue Skeleton (C10 iter-14).
//
// Loading placeholder that pulses subtly against the current surface.
// Slightly more truthful than a bare "Loading…" text node — the pulse
// signals "this rectangle will fill in" rather than "the page might
// be broken".
//
// Uses shadcn's `bg-muted` so it works on both the canvas surface and
// inside cards without a border. Consumers set width/height (and
// optionally corner radius) via the `class` prop.
//
// Example:
//   <Skeleton class="h-4 w-32" />                 // one text-line placeholder
//   <Skeleton class="h-24 w-full rounded-lg" />   // a card body placeholder
const props = defineProps<{
  class?: HTMLAttributes['class'];
}>();

const cls = computed(() => cn('animate-pulse rounded-md bg-muted', props.class));
</script>

<template>
  <div :class="cls" aria-hidden="true" data-slot="skeleton" />
</template>
