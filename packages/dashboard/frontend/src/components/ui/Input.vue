<script setup lang="ts">
import type { HTMLAttributes } from 'vue';
import { computed } from 'vue';
import { cn } from '@/lib/utils';

// shadcn-vue Input (C5 iter-7).
// Migrated onto shadcn semantic tokens (bg-background / border-input /
// text-muted-foreground / ring-ring). The previous primitive tracked
// focused state with a ref to swap border colours — replaced by the
// standard focus-visible ring so keyboard users get a proper affordance
// even before typing.
const props = defineProps<{
  modelValue?: string | number;
  type?: string;
  placeholder?: string;
  disabled?: boolean;
  readonly?: boolean;
  autocomplete?: string;
  autofocus?: boolean;
  id?: string;
  class?: HTMLAttributes['class'];
  invalid?: boolean;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: string];
  blur: [ev: FocusEvent];
  focus: [ev: FocusEvent];
}>();

const cls = computed(() =>
  cn(
    'flex w-full h-10 px-3 py-2 text-sm rounded-md bg-background',
    'border border-input transition-colors duration-150',
    'placeholder:text-muted-foreground',
    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background',
    'disabled:cursor-not-allowed disabled:bg-muted disabled:text-muted-foreground disabled:opacity-60',
    props.invalid && 'border-destructive focus-visible:ring-destructive',
    props.class,
  ),
);
</script>

<template>
  <input
    :id="id"
    :type="type ?? 'text'"
    :value="modelValue"
    :placeholder="placeholder"
    :disabled="disabled"
    :readonly="readonly"
    :autocomplete="autocomplete"
    :autofocus="autofocus"
    :aria-invalid="invalid || undefined"
    :class="cls"
    @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)"
    @focus="(ev) => emit('focus', ev)"
    @blur="(ev) => emit('blur', ev)"
  />
</template>
