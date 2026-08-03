<script setup lang="ts">
import type { HTMLAttributes } from 'vue';
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { cn } from '@/lib/utils';

// shadcn-vue Dialog (C10 iter-15).
//
// Standalone Vue-native Dialog primitive — no reka-ui / headlessui
// dependency (Aurora keeps its footprint small; adding a Vue popover
// library for one modal isn't worth the install cost).
//
// Provides everything shadcn's Dialog does at the interaction level:
//   * v-model:open two-way binding
//   * Teleport to <body> so the overlay always paints on top
//   * ESC to close (document-level listener while open)
//   * Backdrop click to close (opt-out via :dismissable="false")
//   * Focus trap — first focusable inside the panel receives focus on
//     mount; Tab / Shift+Tab cycles inside the panel; ESC unwind
//     returns focus to the last-focused element on the page.
//   * Body scroll lock while open.
//   * ARIA: role="dialog" + aria-modal + aria-labelledby / describedby
//     wired via named slots (title, description).
//
// Named slots:
//   default     — main content
//   title       — receives an auto-generated id via aria-labelledby
//   description — receives an auto-generated id via aria-describedby
//   footer      — button row at the bottom (styled to right-align)
//
// Example:
//   <Dialog v-model:open="showRecovery">
//     <template #title>Password recovery</template>
//     <template #description>Recovery coming shortly…</template>
//     <template #footer>
//       <Button @click="showRecovery = false">Got it</Button>
//     </template>
//   </Dialog>

const props = withDefaults(
  defineProps<{
    open: boolean;
    dismissable?: boolean;    // default true — backdrop click + ESC close
    class?: HTMLAttributes['class'];
  }>(),
  { dismissable: true },
);

const emit = defineEmits<{
  'update:open': [v: boolean];
  close: [];
}>();

const dialogId = `aurora-dialog-${Math.random().toString(36).slice(2, 8)}`;
const titleId = `${dialogId}-title`;
const descriptionId = `${dialogId}-description`;

const panelRef = ref<HTMLElement | null>(null);
const previouslyFocused = ref<HTMLElement | null>(null);

const dismissable = computed(() => props.dismissable);

function close() {
  if (!dismissable.value) return;
  emit('update:open', false);
  emit('close');
}

function onOverlayClick(ev: MouseEvent) {
  // Only close if the click actually landed on the overlay itself,
  // not on a child (the panel).
  if (ev.target === ev.currentTarget) close();
}

function onKeydown(ev: KeyboardEvent) {
  if (!props.open) return;
  if (ev.key === 'Escape') {
    ev.stopPropagation();
    close();
    return;
  }
  if (ev.key !== 'Tab' || !panelRef.value) return;

  // Focus trap — cycle within panel focusables.
  const focusables = panelRef.value.querySelectorAll<HTMLElement>(
    'a[href], area[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), button:not([disabled]), iframe, [tabindex]:not([tabindex="-1"]), [contentEditable=true]',
  );
  if (focusables.length === 0) return;

  const first = focusables[0];
  const last = focusables[focusables.length - 1];
  const active = document.activeElement as HTMLElement | null;

  if (ev.shiftKey && active === first) {
    ev.preventDefault();
    last.focus();
  } else if (!ev.shiftKey && active === last) {
    ev.preventDefault();
    first.focus();
  }
}

watch(
  () => props.open,
  async (isOpen) => {
    if (isOpen) {
      previouslyFocused.value = document.activeElement as HTMLElement | null;
      document.body.style.overflow = 'hidden';
      document.addEventListener('keydown', onKeydown, true);
      await nextTick();
      // Focus the first focusable in the panel; fall back to the panel.
      const first = panelRef.value?.querySelector<HTMLElement>(
        'input, select, textarea, button, [tabindex]:not([tabindex="-1"]), a[href]',
      );
      (first ?? panelRef.value)?.focus();
    } else {
      document.body.style.overflow = '';
      document.removeEventListener('keydown', onKeydown, true);
      previouslyFocused.value?.focus?.();
      previouslyFocused.value = null;
    }
  },
  { immediate: true },
);

onBeforeUnmount(() => {
  // Guarantee scroll lock + listener are released even if the component
  // vanishes while `open` was still true (route change, parent v-if flip).
  document.removeEventListener('keydown', onKeydown, true);
  if (props.open) document.body.style.overflow = '';
});

const panelCls = computed(() =>
  cn(
    'relative max-w-md w-full bg-card text-card-foreground border border-border rounded-lg shadow-lg p-6',
    'focus:outline-none',
    'animate-in fade-in-0 zoom-in-95 duration-150',
    props.class,
  ),
);
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4 animate-in fade-in-0 duration-150"
      role="presentation"
      data-slot="dialog-overlay"
      @click="onOverlayClick"
    >
      <div
        ref="panelRef"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="$slots.title ? titleId : undefined"
        :aria-describedby="$slots.description ? descriptionId : undefined"
        tabindex="-1"
        data-slot="dialog-content"
        :class="panelCls"
      >
        <div v-if="$slots.title || $slots.description" class="mb-4 space-y-1.5">
          <h2 v-if="$slots.title" :id="titleId" class="text-lg font-medium leading-none">
            <slot name="title" />
          </h2>
          <p v-if="$slots.description" :id="descriptionId" class="text-sm text-muted-foreground">
            <slot name="description" />
          </p>
        </div>

        <div class="text-sm text-foreground">
          <slot />
        </div>

        <div v-if="$slots.footer" class="mt-6 flex justify-end gap-2">
          <slot name="footer" />
        </div>
      </div>
    </div>
  </Teleport>
</template>
