<script setup lang="ts">
import { useOnboardingStore, STEPS, STEP_LABELS } from '@/stores/onboarding';
import Progress from '@/components/ui/Progress.vue';
import { computed } from 'vue';
import { useRouter } from 'vue-router';

const store = useOnboardingStore();
const router = useRouter();

const stepList = computed(() =>
  STEPS.map((id, i) => ({
    id,
    label: STEP_LABELS[id],
    index: i,
    active: store.currentStep === id,
    completed: store.completed.has(id),
  })),
);

function goTo(id: (typeof STEPS)[number]): void {
  store.goTo(id);
  router.push(`/onboarding/${id}`);
}
</script>

<template>
  <div class="min-h-screen bg-canvas grid grid-cols-[320px_1fr]">
    <!-- Left rail: steps -->
    <aside class="bg-surface border-r border-line/60 flex flex-col">
      <div class="px-8 pt-8 pb-6 border-b border-line/60">
        <div class="flex items-center gap-2.5">
          <svg viewBox="0 0 32 32" class="w-6 h-6">
            <rect width="32" height="32" rx="6" fill="var(--color-ink)"/>
            <path d="M8 22 L16 8 L24 22" stroke="#FAF9F6" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
            <circle cx="16" cy="22" r="1.5" fill="var(--color-accent)"/>
          </svg>
          <span class="font-serif text-lg leading-none text-ink">Aurora</span>
        </div>
        <div class="mt-1 eyebrow">first-run setup</div>
      </div>

      <div class="px-8 py-6">
        <div class="eyebrow mb-3">Progress</div>
        <Progress :value="store.progress" />
        <div class="mt-2 text-xs text-ink-4">
          Step {{ store.stepIndex + 1 }} of {{ stepList.length }}
        </div>
      </div>

      <nav class="flex-1 px-4 pb-4">
        <button
          v-for="step in stepList"
          :key="step.id"
          type="button"
          :disabled="!step.completed && !step.active && step.index > store.stepIndex"
          class="w-full text-left flex items-start gap-3 px-4 py-2.5 rounded-md transition-colors duration-150 disabled:opacity-40 disabled:pointer-events-none"
          :class="step.active
            ? 'bg-surface-2 text-ink'
            : 'text-ink-3 hover:text-ink hover:bg-surface-2/60'"
          @click="goTo(step.id)"
        >
          <span
            class="w-5 h-5 rounded-full border flex items-center justify-center text-[10px] mt-0.5 shrink-0"
            :class="step.completed
              ? 'bg-[var(--color-ink)] border-[var(--color-ink)] text-white'
              : step.active
                ? 'border-[var(--color-ink)] text-[var(--color-ink)]'
                : 'border-[var(--color-line)] text-[var(--color-ink-4)]'"
          >
            <svg v-if="step.completed" viewBox="0 0 12 12" class="w-2.5 h-2.5" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M2 6 L5 9 L10 3" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            <span v-else>{{ step.index + 1 }}</span>
          </span>
          <span class="text-sm leading-snug pt-0.5">{{ step.label }}</span>
        </button>
      </nav>

      <div class="px-8 py-5 border-t border-line/60 text-xs text-ink-4">
        You can restart onboarding any time from Settings.
      </div>
    </aside>

    <!-- Right pane: step content -->
    <main class="flex flex-col">
      <div class="max-w-2xl mx-auto w-full px-12 py-16 flex-1 anim-enter" :key="store.currentStep">
        <router-view />
      </div>
    </main>
  </div>
</template>
