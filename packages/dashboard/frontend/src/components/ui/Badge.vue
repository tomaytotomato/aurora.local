<script setup lang="ts">
import { cn } from '@/lib/utils';
import { computed } from 'vue';

type Tone = 'ok' | 'warn' | 'err' | 'info' | 'neutral';

const props = defineProps<{
  tone?: Tone;
  class?: string;
}>();

const tone = computed(() => props.tone ?? 'neutral');

const styles = computed(() => {
  const map: Record<Tone, string> = {
    ok: 'bg-[var(--color-ok-bg)] text-[var(--color-ok-fg)]',
    warn: 'bg-[var(--color-warn-bg)] text-[var(--color-warn-fg)]',
    err: 'bg-[var(--color-err-bg)] text-[var(--color-err-fg)]',
    info: 'bg-[var(--color-info-bg)] text-[var(--color-info-fg)]',
    neutral: 'bg-[var(--color-surface-2)] text-[var(--color-ink-2)]',
  };
  return map[tone.value];
});

const cls = computed(() =>
  cn(
    'inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full',
    'text-[0.6875rem] font-medium uppercase tracking-[0.08em]',
    styles.value,
    props.class,
  ),
);
</script>

<template>
  <span :class="cls">
    <span
      v-if="tone && tone !== 'neutral'"
      class="inline-block w-1.5 h-1.5 rounded-full"
      :style="{ backgroundColor: 'currentColor' }"
    />
    <slot />
  </span>
</template>
