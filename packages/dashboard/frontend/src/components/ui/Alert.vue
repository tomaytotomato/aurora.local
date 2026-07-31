<script setup lang="ts">
import { cn } from '@/lib/utils';
import { computed } from 'vue';

type Tone = 'info' | 'warn' | 'err' | 'ok' | 'neutral';

const props = defineProps<{
  tone?: Tone;
  title?: string;
  class?: string;
}>();

const tone = computed(() => props.tone ?? 'neutral');

const styles = computed(() => {
  const map: Record<Tone, string> = {
    info: 'bg-[var(--color-info-bg)] text-[var(--color-info-fg)] border-[var(--color-info-fg)]/15',
    warn: 'bg-[var(--color-warn-bg)] text-[var(--color-warn-fg)] border-[var(--color-warn-fg)]/15',
    err: 'bg-[var(--color-err-bg)] text-[var(--color-err-fg)] border-[var(--color-err-fg)]/15',
    ok: 'bg-[var(--color-ok-bg)] text-[var(--color-ok-fg)] border-[var(--color-ok-fg)]/15',
    neutral: 'bg-[var(--color-surface-2)] text-[var(--color-ink-2)] border-[var(--color-line)]',
  };
  return map[tone.value];
});

const cls = computed(() => cn('rounded-md border px-4 py-3 text-sm', styles.value, props.class));
</script>

<template>
  <div :class="cls" role="status">
    <div v-if="title" class="font-medium mb-1">{{ title }}</div>
    <div><slot /></div>
  </div>
</template>
