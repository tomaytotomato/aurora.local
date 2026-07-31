<script setup lang="ts">
import { cn } from '@/lib/utils';
import { computed, ref } from 'vue';

const props = defineProps<{
  modelValue?: string | number;
  type?: string;
  placeholder?: string;
  disabled?: boolean;
  readonly?: boolean;
  autocomplete?: string;
  autofocus?: boolean;
  id?: string;
  class?: string;
  invalid?: boolean;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: string];
  blur: [ev: FocusEvent];
  focus: [ev: FocusEvent];
}>();

const focused = ref(false);

const cls = computed(() =>
  cn(
    'w-full h-10 px-3 text-sm rounded-md bg-[var(--color-surface)]',
    'border transition-colors duration-150',
    props.invalid
      ? 'border-[var(--color-err-fg)]/40'
      : focused.value
        ? 'border-[var(--color-ink)]'
        : 'border-[var(--color-line)]',
    'placeholder:text-[var(--color-ink-4)]',
    'disabled:bg-[var(--color-surface-2)] disabled:text-[var(--color-ink-3)]',
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
    :class="cls"
    @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)"
    @focus="(ev) => { focused = true; emit('focus', ev); }"
    @blur="(ev) => { focused = false; emit('blur', ev); }"
  />
</template>
