<script setup lang="ts" generic="T extends string">
import type { HTMLAttributes } from 'vue';
import { computed, nextTick } from 'vue';
import { cn } from '@/lib/utils';

// shadcn-vue Tabs (C6 iter-8), the single tab/segmented-strip primitive.
//
// Aurora's single-component API (`v-model` + `tabs` array + default slot
// for panels) is preserved. Used both as tabs-with-panels (PackageDetail,
// VpnView, OnboardingDns — pass a default slot) and as a page-level filter
// strip (PackagesList, OnboardingPackages — no slot; the filtered content
// lives below the component). The two hand-rolled filter strips were
// folded onto this so there is one keyboard-accessible implementation.
//
//   size  'md' (default) | 'sm'  — sm is the denser onboarding strip.
//   hint  optional dim count/label after the tab title (e.g. "16").
//   arrow-key navigation moves selection + focus, per the WAI-ARIA tab
//   pattern; without it the roving tabindex left inactive tabs
//   keyboard-unreachable.
const props = withDefaults(
  defineProps<{
    modelValue: T;
    tabs: readonly { value: T; label: string; hint?: string }[];
    class?: HTMLAttributes['class'];
    size?: 'sm' | 'md';
  }>(),
  { size: 'md' },
);

const emit = defineEmits<{ 'update:modelValue': [v: T] }>();

const listCls = computed(() =>
  cn('flex items-center border-b border-border', props.class),
);

const triggerCls = computed(() =>
  props.size === 'sm' ? 'px-3 py-2 text-xs' : 'px-4 py-3 text-sm',
);

function onKeydown(e: KeyboardEvent): void {
  const idx = props.tabs.findIndex((t) => t.value === props.modelValue);
  if (idx < 0) return;
  const last = props.tabs.length - 1;
  let next = idx;
  switch (e.key) {
    case 'ArrowRight':
    case 'ArrowDown': next = idx === last ? 0 : idx + 1; break;
    case 'ArrowLeft':
    case 'ArrowUp': next = idx === 0 ? last : idx - 1; break;
    case 'Home': next = 0; break;
    case 'End': next = last; break;
    default: return;
  }
  e.preventDefault();
  emit('update:modelValue', props.tabs[next].value);
  const list = e.currentTarget as HTMLElement;
  void nextTick(() => {
    list.querySelectorAll<HTMLElement>('[role="tab"]')[next]?.focus();
  });
}
</script>

<template>
  <div>
    <div :class="listCls" role="tablist" @keydown="onKeydown">
      <button
        v-for="t in tabs"
        :key="t.value"
        role="tab"
        type="button"
        :aria-selected="modelValue === t.value"
        :tabindex="modelValue === t.value ? 0 : -1"
        class="relative transition-colors duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background rounded-sm"
        :class="[
          triggerCls,
          modelValue === t.value ? 'text-foreground' : 'text-muted-foreground hover:text-foreground',
        ]"
        @click="emit('update:modelValue', t.value)"
      >
        {{ t.label }}
        <!-- <small>, not <span>: `.on-photo-tabs button > span` paints the
             active underline white and would swallow a span hint. -->
        <small v-if="t.hint" class="ml-1.5 tabular-nums opacity-70">{{ t.hint }}</small>
        <span
          v-if="modelValue === t.value"
          class="absolute inset-x-0 -bottom-px h-px bg-foreground"
          aria-hidden="true"
        />
      </button>
    </div>
    <div v-if="$slots.default" class="pt-6">
      <slot />
    </div>
  </div>
</template>
