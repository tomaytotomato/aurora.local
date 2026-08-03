<script setup lang="ts">
import type { HTMLAttributes } from 'vue';
import { computed } from 'vue';
import { cn } from '@/lib/utils';

// Aurora DropdownMenuItem — one row inside a DropdownMenu.
// Styled as shadcn menu item (focus:bg-muted, hover:bg-muted) and
// carries `data-menu-item` so the parent DropdownMenu's arrow-key
// navigation can find it.

const props = defineProps<{
  disabled?: boolean;
  destructive?: boolean;
  class?: HTMLAttributes['class'];
}>();

const emit = defineEmits<{ select: [] }>();

const cls = computed(() =>
  cn(
    'flex w-full items-center gap-2 px-3 py-1.5 text-sm outline-none text-left',
    'hover:bg-muted focus:bg-muted transition-colors',
    'disabled:pointer-events-none disabled:opacity-40',
    props.destructive && 'text-destructive hover:bg-destructive/10 focus:bg-destructive/10',
    props.class,
  ),
);

function onClick(ev: MouseEvent) {
  if (props.disabled) {
    ev.preventDefault();
    return;
  }
  emit('select');
}
</script>

<template>
  <button
    type="button"
    role="menuitem"
    :disabled="disabled"
    :class="cls"
    data-menu-item
    data-slot="dropdown-item"
    @click="onClick"
  >
    <slot />
  </button>
</template>
