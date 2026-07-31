<script setup lang="ts" generic="T extends string">
import { cn } from '@/lib/utils';
import { computed } from 'vue';

const props = defineProps<{
  modelValue: T;
  tabs: readonly { value: T; label: string; hint?: string }[];
  class?: string;
}>();

const emit = defineEmits<{ 'update:modelValue': [v: T] }>();

const listCls = computed(() =>
  cn(
    'flex items-center border-b border-[var(--color-line)]',
    props.class,
  ),
);
</script>

<template>
  <div>
    <div :class="listCls" role="tablist">
      <button
        v-for="t in tabs"
        :key="t.value"
        role="tab"
        type="button"
        :aria-selected="modelValue === t.value"
        class="relative px-4 py-3 text-sm transition-colors duration-150"
        :class="modelValue === t.value
          ? 'text-[var(--color-ink)]'
          : 'text-[var(--color-ink-3)] hover:text-[var(--color-ink-2)]'"
        @click="emit('update:modelValue', t.value)"
      >
        {{ t.label }}
        <span
          v-if="modelValue === t.value"
          class="absolute inset-x-0 -bottom-px h-px bg-[var(--color-ink)]"
        />
      </button>
    </div>
    <div class="pt-6">
      <slot />
    </div>
  </div>
</template>
