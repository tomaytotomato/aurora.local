<script setup lang="ts" generic="T extends string">
import type { HTMLAttributes } from 'vue';
import { computed } from 'vue';
import { cn } from '@/lib/utils';

// shadcn-vue Tabs (C6 iter-8).
//
// Aurora's single-component Tabs API (`v-model` + `tabs` array + default
// slot for panels) is preserved — only two callers (PackageDetail,
// OnboardingDns) and both benefit from the simpler surface vs shadcn's
// three-primitive TabsList / TabsTrigger / TabsContent split.
//
// Migration is TOKEN-LEVEL:
//   border-[var(--color-line)]    → border-border
//   text-[var(--color-ink)]       → text-foreground
//   text-[var(--color-ink-3)]     → text-muted-foreground
//   bg-[var(--color-ink)]         → bg-foreground   (underline indicator)
// Plus a proper focus-visible:ring keyboard affordance and roving
// tabindex (only the active trigger is tabbable) to match ARIA
// tab pattern.
const props = defineProps<{
  modelValue: T;
  tabs: readonly { value: T; label: string; hint?: string }[];
  class?: HTMLAttributes['class'];
}>();

const emit = defineEmits<{ 'update:modelValue': [v: T] }>();

const listCls = computed(() =>
  cn('flex items-center border-b border-border', props.class),
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
        :tabindex="modelValue === t.value ? 0 : -1"
        class="relative px-4 py-3 text-sm transition-colors duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background rounded-sm"
        :class="modelValue === t.value
          ? 'text-foreground'
          : 'text-muted-foreground hover:text-foreground'"
        @click="emit('update:modelValue', t.value)"
      >
        {{ t.label }}
        <span
          v-if="modelValue === t.value"
          class="absolute inset-x-0 -bottom-px h-px bg-foreground"
          aria-hidden="true"
        />
      </button>
    </div>
    <div class="pt-6">
      <slot />
    </div>
  </div>
</template>
