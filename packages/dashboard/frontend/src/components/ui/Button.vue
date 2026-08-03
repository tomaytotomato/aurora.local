<script setup lang="ts">
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '@/lib/utils';
import { computed } from 'vue';

const button = cva(
  'inline-flex items-center justify-center gap-2 whitespace-nowrap font-medium transition-all duration-150 ' +
    'focus-visible:outline-none disabled:pointer-events-none disabled:opacity-40 active:scale-[0.99]',
  {
    variants: {
      variant: {
        primary:
          'bg-[var(--color-ink)] text-[var(--color-on-ink)] hover:bg-[var(--color-ink-hover)] rounded-md',
        secondary:
          'bg-[var(--color-surface)] text-[var(--color-ink)] border border-[var(--color-line)] hover:bg-[var(--color-surface-2)] rounded-md',
        ghost:
          'bg-transparent text-[var(--color-ink-2)] hover:text-[var(--color-ink)] hover:bg-[var(--color-surface-2)] rounded-md',
        link: 'bg-transparent text-[var(--color-ink)] underline-offset-4 hover:underline p-0',
        danger:
          'bg-transparent border border-[var(--color-line)] text-[var(--color-err-fg)] hover:bg-[var(--color-err-bg)] rounded-md',
        accent:
          'bg-[var(--color-accent)] text-[var(--color-on-accent)] hover:bg-[var(--color-accent-hover)] rounded-md',
      },
      size: {
        sm: 'h-8 px-3 text-xs',
        md: 'h-10 px-4 text-sm',
        lg: 'h-11 px-5 text-sm',
      },
    },
    defaultVariants: {
      variant: 'primary',
      size: 'md',
    },
  },
);

type ButtonVariants = VariantProps<typeof button>;

const props = defineProps<{
  variant?: ButtonVariants['variant'];
  size?: ButtonVariants['size'];
  type?: 'button' | 'submit' | 'reset';
  disabled?: boolean;
  loading?: boolean;
  class?: string;
}>();

const classes = computed(() =>
  cn(button({ variant: props.variant, size: props.size }), props.class),
);
</script>

<template>
  <button
    :type="type ?? 'button'"
    :class="classes"
    :disabled="disabled || loading"
    :aria-busy="loading || undefined"
  >
    <span v-if="loading" class="inline-block h-3 w-3 border border-current border-t-transparent rounded-full animate-spin" />
    <slot />
  </button>
</template>
