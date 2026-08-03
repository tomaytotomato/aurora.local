<script setup lang="ts">
import type { HTMLAttributes } from 'vue';
import { computed } from 'vue';
import { cn } from '@/lib/utils';

// shadcn-vue Checkbox (C5 iter-7).
// Migrated onto shadcn tokens: checked → bg-primary / text-primary-
// foreground / border-primary; unchecked → bg-background / border-input;
// focus ring → ring-ring. Interaction pattern (button + role=checkbox
// + aria-checked) preserved from Aurora's original because a native
// <input type=checkbox> is a pain to style consistently under shadcn's
// warm-monochrome palette.
const props = defineProps<{
  modelValue: boolean;
  disabled?: boolean;
  id?: string;
  class?: HTMLAttributes['class'];
}>();

const emit = defineEmits<{ 'update:modelValue': [v: boolean] }>();

const cls = computed(() =>
  cn(
    'inline-flex items-center justify-center h-4 w-4 rounded-[3px] border transition-colors duration-150',
    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background',
    props.modelValue
      ? 'bg-primary border-primary text-primary-foreground'
      : 'bg-background border-input hover:border-muted-foreground',
    props.disabled && 'opacity-40 pointer-events-none',
    props.class,
  ),
);
</script>

<template>
  <button
    :id="id"
    role="checkbox"
    type="button"
    :aria-checked="modelValue"
    :class="cls"
    :disabled="disabled"
    @click="emit('update:modelValue', !modelValue)"
  >
    <svg
      v-if="modelValue"
      viewBox="0 0 12 12"
      class="w-2.5 h-2.5"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      aria-hidden="true"
    >
      <path d="M2 6 L5 9 L10 3" stroke-linecap="round" stroke-linejoin="round" />
    </svg>
  </button>
</template>
