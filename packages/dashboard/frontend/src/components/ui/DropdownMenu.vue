<script setup lang="ts">
import type { HTMLAttributes } from 'vue';
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { cn } from '@/lib/utils';

// shadcn-vue DropdownMenu (C10 iter-20).
//
// Lightweight self-contained menu — one Vue component with named
// slots for the trigger + menu items. Aurora doesn't have a compound
// menu use case yet (nested submenus, checkboxes, radios); when one
// appears we'll expand to the full shadcn compound split.
//
// Provides:
//   * `#trigger` slot (unwrapped button/anchor rendered by the caller).
//   * `#default` slot for the menu content — usually a stack of
//     <button data-menu-item> or router-link elements.
//   * Click-to-toggle, ESC-to-close, click-outside-to-close.
//   * Focus returns to the trigger on close (a11y — user doesn't get
//     orphaned in the tree).
//   * Alignment via :align="'left' | 'right'" (default right — TopBar
//     puts avatar on the right).
//   * ARIA: role=menu / role=menuitem / aria-haspopup / aria-expanded.

const props = withDefaults(
  defineProps<{
    align?: 'left' | 'right';
    class?: HTMLAttributes['class'];
    contentClass?: HTMLAttributes['class'];
  }>(),
  { align: 'right' },
);

const emit = defineEmits<{ 'update:open': [v: boolean] }>();

const open = ref(false);
const triggerRef = ref<HTMLElement | null>(null);
const rootRef = ref<HTMLElement | null>(null);

function toggle() {
  open.value = !open.value;
  emit('update:open', open.value);
}
function close() {
  if (!open.value) return;
  open.value = false;
  emit('update:open', false);
  triggerRef.value?.focus?.();
}

function onDocClick(ev: MouseEvent) {
  if (!open.value || !rootRef.value) return;
  if (!rootRef.value.contains(ev.target as Node)) close();
}
function onDocKey(ev: KeyboardEvent) {
  if (!open.value) return;
  if (ev.key === 'Escape') {
    ev.stopPropagation();
    close();
    return;
  }
  // Arrow-key nav: focus next/prev menu item.
  if (ev.key !== 'ArrowDown' && ev.key !== 'ArrowUp') return;
  const items = rootRef.value?.querySelectorAll<HTMLElement>('[data-menu-item]');
  if (!items || items.length === 0) return;
  const arr = Array.from(items);
  const idx = arr.indexOf(document.activeElement as HTMLElement);
  ev.preventDefault();
  if (ev.key === 'ArrowDown') arr[(idx + 1) % arr.length].focus();
  else arr[(idx - 1 + arr.length) % arr.length].focus();
}

watch(open, (isOpen) => {
  if (isOpen) {
    document.addEventListener('click', onDocClick, true);
    document.addEventListener('keydown', onDocKey, true);
    // Focus the first menu item so keyboard users can navigate immediately.
    requestAnimationFrame(() => {
      const first = rootRef.value?.querySelector<HTMLElement>('[data-menu-item]');
      first?.focus();
    });
  } else {
    document.removeEventListener('click', onDocClick, true);
    document.removeEventListener('keydown', onDocKey, true);
  }
});

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick, true);
  document.removeEventListener('keydown', onDocKey, true);
});

const rootCls = computed(() => cn('relative inline-block', props.class));
const contentCls = computed(() =>
  cn(
    'absolute z-50 min-w-40 rounded-md border border-border bg-popover text-popover-foreground shadow-lg',
    'py-1 mt-1',
    'animate-in fade-in-0 zoom-in-95 duration-100',
    props.align === 'right' ? 'right-0' : 'left-0',
    props.contentClass,
  ),
);
</script>

<template>
  <div ref="rootRef" :class="rootCls" data-slot="dropdown-menu">
    <div
      ref="triggerRef"
      class="inline-flex"
      aria-haspopup="menu"
      :aria-expanded="open"
      data-slot="dropdown-trigger"
      @click="toggle"
    >
      <slot name="trigger" :open="open" />
    </div>
    <div v-if="open" role="menu" :class="contentCls" data-slot="dropdown-content">
      <slot />
    </div>
  </div>
</template>
