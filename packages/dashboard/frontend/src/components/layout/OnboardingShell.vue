<script setup lang="ts">
import { useOnboardingStore, STEPS, STEP_LABELS } from '@/stores/onboarding';
import Progress from '@/components/ui/Progress.vue';
import AuroraBackground from '@/components/AuroraBackground.vue';
import AuroraCredit from '@/components/AuroraCredit.vue';
import { computed, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import type { OnboardingStepId } from '@/api/onboarding';

const store = useOnboardingStore();
const router = useRouter();
const route = useRoute();

// Keep store.currentStep in lockstep with the URL. This is what stops the
// step-drift bug: previously the store cursor could disagree with the URL
// (e.g. after a refresh that force-set the cursor from the server), and
// then "Continue" would advance from the store cursor, not from the visible
// page. Now the URL is the single source of truth.
watch(
  () => route.path,
  (path) => {
    const seg = path.split('/')[2] as OnboardingStepId | undefined;
    if (seg) store.syncFromRoute(seg);
  },
  { immediate: true },
);

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
  <!-- Aurora fills the whole viewport; every panel below floats over it.
       One photo per day, deterministic per session. -->
  <AuroraBackground scrim="medium" />

  <div class="onboarding-shell relative z-10 min-h-screen grid grid-cols-[320px_1fr]">
    <!-- Left rail: steps. Dark translucent glass over the photo. -->
    <aside class="rail flex flex-col">
      <div class="px-8 pt-8 pb-6 border-b border-white/10">
        <div class="flex items-center gap-2.5">
          <svg viewBox="0 0 32 32" class="w-6 h-6">
            <rect width="32" height="32" rx="6" fill="rgba(255,255,255,0.92)"/>
            <path d="M8 22 L16 8 L24 22" stroke="#14120f" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
            <circle cx="16" cy="22" r="1.5" fill="var(--color-accent)"/>
          </svg>
          <span class="font-serif text-lg leading-none text-white/95">Aurora</span>
        </div>
        <div class="mt-1 rail-eyebrow">first-run setup</div>
      </div>

      <div class="px-8 py-6">
        <div class="rail-eyebrow mb-3">Progress</div>
        <Progress :value="store.progress" class="rail-progress" />
        <div class="mt-2 text-xs text-white/50">
          Step {{ store.stepIndex + 1 }} of {{ stepList.length }}
        </div>
      </div>

      <nav class="flex-1 px-4 pb-4">
        <button
          v-for="step in stepList"
          :key="step.id"
          type="button"
          :disabled="!step.completed && !step.active && step.index > store.stepIndex"
          class="rail-step w-full text-left flex items-start gap-3 px-4 py-2.5 rounded-md transition-colors duration-150 disabled:opacity-30 disabled:pointer-events-none"
          :class="step.active ? 'is-active' : ''"
          @click="goTo(step.id)"
        >
          <span
            class="w-5 h-5 rounded-full border flex items-center justify-center text-[10px] mt-0.5 shrink-0"
            :class="step.completed
              ? 'bg-white/95 border-white/95 text-[#14120f]'
              : step.active
                ? 'border-white/90 text-white/95'
                : 'border-white/25 text-white/40'"
          >
            <svg v-if="step.completed" viewBox="0 0 12 12" class="w-2.5 h-2.5" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M2 6 L5 9 L10 3" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            <span v-else>{{ step.index + 1 }}</span>
          </span>
          <span class="text-sm leading-snug pt-0.5">{{ step.label }}</span>
        </button>
      </nav>

      <div class="px-8 py-5 border-t border-white/10 text-xs text-white/45">
        You can restart onboarding any time from Settings.
      </div>
    </aside>

    <!-- Right pane: step content on a warm glass card so all existing
         editorial typography still reads correctly. -->
    <main class="flex flex-col items-center justify-start py-12">
      <div
        class="content-card w-full max-w-2xl mx-auto px-12 py-14 anim-enter"
        :key="store.currentStep"
      >
        <router-view />
      </div>
      <!-- Photo attribution at the foot of the page. -->
      <AuroraCredit class="w-full max-w-2xl mx-auto mt-auto pt-8" />
    </main>
  </div>
</template>

<style scoped>
/* -- Left rail: dark translucent glass ------------------------------ */
.rail {
  background: rgba(15, 13, 10, 0.55);
  backdrop-filter: blur(24px) saturate(140%);
  -webkit-backdrop-filter: blur(24px) saturate(140%);
  border-right: 1px solid rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.92);
}

.rail-eyebrow {
  font-size: 10px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.5);
  font-weight: 600;
}

.rail-step {
  color: rgba(255, 255, 255, 0.65);
}
.rail-step:not(:disabled):hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.06);
}
.rail-step.is-active {
  color: #fff;
  background: rgba(255, 255, 255, 0.10);
}

/* Progress bar picks up an overridden fill in the dark rail. */
.rail :deep(.progress-track) { background: rgba(255, 255, 255, 0.12); }
.rail :deep(.progress-fill)  { background: rgba(255, 255, 255, 0.85); }

/* -- Content card: warm off-white glass over the photo -------------- */
.content-card {
  background: rgba(250, 249, 246, 0.94);
  backdrop-filter: blur(20px) saturate(120%);
  -webkit-backdrop-filter: blur(20px) saturate(120%);
  border: 1px solid rgba(20, 18, 15, 0.06);
  border-radius: 16px;
  box-shadow:
    0 1px 2px rgba(20, 18, 15, 0.06),
    0 24px 48px -20px rgba(20, 18, 15, 0.35);
}

/* Small screens: sidebar becomes a top bar instead of split-pane. */
@media (max-width: 900px) {
  .onboarding-shell { grid-template-columns: 1fr; }
  .rail { border-right: 0; border-bottom: 1px solid rgba(255, 255, 255, 0.1); }
}
</style>
