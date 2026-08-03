<script setup lang="ts">
import type { HTMLAttributes } from 'vue';
import { computed } from 'vue';
import { cn } from '@/lib/utils';
import { buttonVariants, type ButtonVariants } from './buttonVariants';

// shadcn-vue Button (C3 iter-5).
// Aurora-flavoured variant/size names preserved for caller compatibility;
// colour classes now use shadcn semantic tokens (see buttonVariants.ts).
const props = defineProps<{
  variant?: ButtonVariants['variant'];
  size?: ButtonVariants['size'];
  type?: 'button' | 'submit' | 'reset';
  disabled?: boolean;
  loading?: boolean;
  class?: HTMLAttributes['class'];
}>();

const classes = computed(() =>
  cn(buttonVariants({ variant: props.variant, size: props.size }), props.class),
);
</script>

<template>
  <button
    :type="type ?? 'button'"
    :class="classes"
    :disabled="disabled || loading"
    :aria-busy="loading || undefined"
  >
    <span
      v-if="loading"
      class="inline-block h-3 w-3 border border-current border-t-transparent rounded-full animate-spin"
      aria-hidden="true"
    />
    <slot />
  </button>
</template>
