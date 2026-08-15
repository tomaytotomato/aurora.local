<script setup lang="ts">
import type { HTMLAttributes } from 'vue';
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { cn } from '@/lib/utils';

// shadcn-vue DropdownMenu (C10 iter-20, teleport fix iter-overlays-1).
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
//
// Teleport (iter-overlays-1): the content used to render as an
// `absolute` sibling of the trigger, positioned relative to the
// `relative inline-block` root. That meant any caller sitting inside
// an `overflow-x-auto`/`overflow-hidden` ancestor (the Table wrapper,
// notably — see UsersView's row-actions menu) clipped the menu at the
// ancestor's edge instead of floating above it. Content now teleports
// to <body> and is positioned with `fixed` + a `getBoundingClientRect`
// read off the root, so it always paints above everything regardless
// of what scrolls or clips its DOM ancestors. Alignment is done with a
// `-translate-x-full` transform rather than a measured width, so there
// is no first-frame flash while content is unmeasured.
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
const contentRef = ref<HTMLElement | null>(null);

const position = ref({ top: 0, left: 0 });

function computePosition() {
  const anchor = rootRef.value;
  if (!anchor) return;
  const rect = anchor.getBoundingClientRect();
  position.value = {
    top: rect.bottom + 4,
    left: props.align === 'right' ? rect.right : rect.left,
  };
}

function toggle() {
  open.value = !open.value;
  emit('update:open', open.value);
}
function close() {
  if (!open.value) return;
  open.value = false;
  emit('update:open', false);
  // Return focus to the real interactive element the caller rendered in
  // #trigger (a <button>/<a>), not the wrapper <div> — a div isn't
  // focusable, so the previous `triggerRef.focus()` silently no-op'd and
  // left keyboard users orphaned at the top of the document.
  const inner = triggerRef.value?.querySelector<HTMLElement>(
    'button, a[href], [role="button"], [tabindex]',
  );
  (inner ?? triggerRef.value)?.focus?.();
}

function onDocClick(ev: MouseEvent) {
  if (!open.value) return;
  const target = ev.target as Node;
  // Content now lives under <body> via Teleport, so it's no longer a
  // descendant of rootRef — check both trees before treating the click
  // as "outside".
  const insideRoot = rootRef.value?.contains(target) ?? false;
  const insideContent = contentRef.value?.contains(target) ?? false;
  if (!insideRoot && !insideContent) close();
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
  const items = contentRef.value?.querySelectorAll<HTMLElement>('[data-menu-item]');
  if (!items || items.length === 0) return;
  const arr = Array.from(items);
  const idx = arr.indexOf(document.activeElement as HTMLElement);
  ev.preventDefault();
  if (ev.key === 'ArrowDown') arr[(idx + 1) % arr.length].focus();
  else arr[(idx - 1 + arr.length) % arr.length].focus();
}

// `fixed` positioning is relative to the viewport, so if any scrolling
// ancestor (the Table wrapper, a scrolling card body, ...) moves under
// an open menu, the menu has to follow it or it visibly detaches from
// its trigger. `scroll` doesn't bubble, but a capture-phase listener on
// window still sees it fire on any descendant scroll container, so this
// catches every case without hunting for the specific scroll parent.
function onScrollOrResize() {
  if (!open.value) return;
  computePosition();
}

watch(open, async (isOpen) => {
  if (isOpen) {
    document.addEventListener('click', onDocClick, true);
    document.addEventListener('keydown', onDocKey, true);
    window.addEventListener('scroll', onScrollOrResize, true);
    window.addEventListener('resize', onScrollOrResize);
    computePosition();
    await nextTick();
    // Recompute once the teleported content exists in the DOM in case
    // layout shifted between the first read and now.
    computePosition();
    requestAnimationFrame(() => {
      const first = contentRef.value?.querySelector<HTMLElement>('[data-menu-item]');
      first?.focus();
    });
  } else {
    document.removeEventListener('click', onDocClick, true);
    document.removeEventListener('keydown', onDocKey, true);
    window.removeEventListener('scroll', onScrollOrResize, true);
    window.removeEventListener('resize', onScrollOrResize);
  }
});

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick, true);
  document.removeEventListener('keydown', onDocKey, true);
  window.removeEventListener('scroll', onScrollOrResize, true);
  window.removeEventListener('resize', onScrollOrResize);
});

const rootCls = computed(() => cn('relative inline-block', props.class));
const contentCls = computed(() =>
  cn(
    'fixed z-50 min-w-40 rounded-md border border-border bg-popover text-popover-foreground shadow-lg',
    'py-1',
    'animate-in fade-in-0 zoom-in-95 duration-100',
    // Right-aligned menus anchor their left edge at the trigger's right
    // edge, then pull back by their own width — no measured width
    // needed, so there's nothing to get wrong on the first frame.
    props.align === 'right' ? '-translate-x-full' : '',
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
    <Teleport to="body">
      <div
        v-if="open"
        ref="contentRef"
        role="menu"
        :class="contentCls"
        :style="{ top: `${position.top}px`, left: `${position.left}px` }"
        :data-align="align"
        data-slot="dropdown-content"
      >
        <slot />
      </div>
    </Teleport>
  </div>
</template>
