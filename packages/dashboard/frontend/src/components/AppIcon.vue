<script setup lang="ts">
import type { HTMLAttributes } from 'vue';
import { computed, ref } from 'vue';
import { cn } from '@/lib/utils';

// A package's logo on its card. `src` comes from packageIconUrl(); when it
// is null (the manifest declares no icon) or the asset fails to load (a
// slug with no bundled file), the tile falls back to the package's initial
// so a missing logo never leaves a broken image on the card.
const props = defineProps<{
  src: string | null;
  label: string;
  class?: HTMLAttributes['class'];
}>();

const failed = ref(false);
const showImage = computed(() => !!props.src && !failed.value);
const initial = computed(() => (props.label.trim()[0] ?? '?').toUpperCase());
</script>

<template>
  <span
    :class="cn('inline-flex items-center justify-center shrink-0 h-10 w-10 rounded-lg bg-muted overflow-hidden', props.class)"
    data-test="app-icon"
  >
    <img
      v-if="showImage"
      :src="src!"
      :alt="`${label} logo`"
      class="h-7 w-7 object-contain"
      loading="lazy"
      @error="failed = true"
    />
    <span
      v-else
      class="text-sm font-semibold text-muted-foreground"
      aria-hidden="true"
      data-slot="app-icon-fallback"
    >{{ initial }}</span>
  </span>
</template>
