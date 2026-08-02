<script setup lang="ts">
import { computed } from 'vue';
import { cn } from '@/lib/utils';

const props = defineProps<{
  modelValue: boolean;
  disabled?: boolean;
  id?: string;
  class?: string;
}>();

const emit = defineEmits<{ 'update:modelValue': [v: boolean] }>();

const cls = computed(() =>
  cn(
    'inline-flex items-center justify-center h-4 w-4 rounded-[3px] border transition-colors duration-150',
    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--color-accent)]/40',
    props.modelValue
      ? 'bg-[var(--color-ink)] border-[var(--color-ink)] text-[var(--color-on-ink)]'
      : 'bg-[var(--color-surface)] border-[var(--color-line)] hover:border-[var(--color-ink-3)]',
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
    <svg v-if="modelValue" viewBox="0 0 12 12" class="w-2.5 h-2.5" fill="none" stroke="currentColor" stroke-width="2">
      <path d="M2 6 L5 9 L10 3" stroke-linecap="round" stroke-linejoin="round" />
    </svg>
  </button>
</template>
