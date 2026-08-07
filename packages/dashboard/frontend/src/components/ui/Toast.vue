<script setup lang="ts">
import type { HTMLAttributes } from 'vue';
import { computed } from 'vue';
import { cn } from '@/lib/utils';
import type { ToastVariant } from '@/composables/useToast';

// Aurora Toast card (C10 iter-18).
// Single toast presentation — rendered by Toaster.vue, not directly
// by callers. Callers invoke `toast({ … })` from useToast.
//
// Variants map onto Alert's shadcn token pattern (tint + border-<name>/40 +
// text-<name>) so a success toast reads as the same visual family as a
// success alert. Consistency > cleverness.
//
// Unlike Alert, a toast can never sit inside a Card — Toaster teleports it
// straight to <body> as a floating, fixed-position notification, so it
// always has the app-wide aurora photo (or plain canvas) directly behind
// it. A plain `bg-<name>/10` alpha tint would let that show straight
// through, and in light mode text-<name> on the near-black photo is close
// to unreadable — the same contrast bug Alert avoids by always being
// wrapped in an opaque Card. The tint here is layered as a background-
// *image* (a flat two-stop gradient) on top of an opaque `bg-card` base
// instead of as the background-color itself, so the toast keeps the same
// look while staying fully opaque wherever it renders.

const props = defineProps<{
  title?: string;
  description: string;
  variant?: ToastVariant;
  actionLabel?: string;
  class?: HTMLAttributes['class'];
}>();

const emit = defineEmits<{
  dismiss: [];
  action: [];
}>();

// Errors must interrupt a screen reader (assertive), not wait politely in
// the queue behind whatever it's reading — several call sites use a
// destructive toast as the only failure feedback.
const urgent = computed(() => (props.variant ?? 'default') === 'destructive');

const cls = computed(() => {
  const variant = props.variant ?? 'default';
  const variantCls: Record<ToastVariant, string> = {
    default: 'bg-card text-card-foreground border-border',
    success: 'bg-card bg-gradient-to-r from-success/10 to-success/10 border-success/40 text-success',
    warning: 'bg-card bg-gradient-to-r from-warning/10 to-warning/10 border-warning/40 text-warning',
    destructive: 'bg-card bg-gradient-to-r from-destructive/10 to-destructive/10 border-destructive/40 text-destructive',
  };
  return cn(
    'pointer-events-auto relative w-80 max-w-full rounded-md border p-4 pr-10 shadow-lg',
    'animate-in fade-in-0 slide-in-from-bottom-2 duration-150',
    variantCls[variant],
    props.class,
  );
});
</script>

<template>
  <div
    :class="cls"
    :role="urgent ? 'alert' : 'status'"
    :aria-live="urgent ? 'assertive' : 'polite'"
    data-slot="toast"
    :data-variant="variant ?? 'default'"
  >
    <div v-if="title" class="mb-1 text-sm font-medium leading-none">{{ title }}</div>
    <div class="text-sm">{{ description }}</div>
    <div v-if="actionLabel" class="mt-3">
      <button
        type="button"
        class="text-xs font-medium underline underline-offset-2 hover:no-underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded-sm px-1"
        data-slot="toast-action"
        @click="emit('action')"
      >{{ actionLabel }}</button>
    </div>
    <button
      type="button"
      aria-label="Dismiss notification"
      class="absolute right-2 top-2 rounded-sm p-1 opacity-70 hover:opacity-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      data-slot="toast-dismiss"
      @click="emit('dismiss')"
    >
      <svg viewBox="0 0 24 24" class="h-3.5 w-3.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <line x1="18" y1="6" x2="6" y2="18" />
        <line x1="6" y1="6" x2="18" y2="18" />
      </svg>
    </button>
  </div>
</template>
