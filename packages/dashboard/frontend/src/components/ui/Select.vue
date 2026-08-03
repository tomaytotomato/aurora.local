<script setup lang="ts" generic="T extends string | number">
import type { HTMLAttributes } from 'vue';
import { computed } from 'vue';
import { cn } from '@/lib/utils';

// shadcn-vue Select (C10 iter-16).
//
// Pragmatic styled native <select> — instead of building a full popover-
// listbox (needs a Vue headless UI dep), we keep native semantics for
// free (keyboard nav, screen reader, mobile picker) and layer shadcn
// visual tokens + a chevron indicator on top. Same look as shadcn's
// canonical SelectTrigger, ~50 lines instead of 200+.
//
// v-model binds the selected value. Options are declared inline via
// { value, label } tuples. Type parameter <T> so numeric selects
// (Log tail size) stay type-safe alongside string selects (Snooze
// choice keys).
//
// Example:
//   <Select v-model="tail"
//           :options="TAIL_OPTIONS.map(n => ({ value: n, label: String(n) }))" />

const props = defineProps<{
  modelValue: T;
  options: readonly { value: T; label: string; disabled?: boolean }[];
  id?: string;
  name?: string;
  disabled?: boolean;
  class?: HTMLAttributes['class'];
  ariaLabel?: string;
}>();

const emit = defineEmits<{ 'update:modelValue': [v: T] }>();

const cls = computed(() =>
  cn(
    // Same shape as Input + Button: shadcn tokens, focus ring, h-10.
    'appearance-none pr-8 pl-3 h-10 w-full rounded-md text-sm',
    'bg-card text-foreground border border-input',
    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background',
    'disabled:cursor-not-allowed disabled:bg-muted disabled:text-muted-foreground disabled:opacity-60',
    props.class,
  ),
);

function onChange(ev: Event) {
  const raw = (ev.target as HTMLSelectElement).value;
  // Preserve the input value's type — if the current modelValue is
  // numeric, coerce the string DOM value back to a number.
  const coerced = typeof props.modelValue === 'number' ? (Number(raw) as T) : (raw as T);
  emit('update:modelValue', coerced);
}
</script>

<template>
  <div class="relative inline-block" data-slot="select-wrapper">
    <select
      :id="id"
      :name="name"
      :value="modelValue"
      :disabled="disabled"
      :aria-label="ariaLabel"
      :class="cls"
      data-slot="select-trigger"
      @change="onChange"
    >
      <option
        v-for="opt in options"
        :key="String(opt.value)"
        :value="opt.value"
        :disabled="opt.disabled"
      >
        {{ opt.label }}
      </option>
    </select>
    <!-- Chevron rendered as an overlaid SVG rather than a bg-image so
         tailwind-merge doesn't fight `bg-card` when a caller merges
         both onto the same element. Positioned absolute + pointer-events-none
         so it never intercepts clicks meant for the native picker. -->
    <svg
      viewBox="0 0 24 24"
      class="pointer-events-none absolute right-2.5 top-1/2 -translate-y-1/2 h-3 w-3 text-muted-foreground"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
      data-slot="select-chevron"
    >
      <polyline points="6 9 12 15 18 9" />
    </svg>
  </div>
</template>
