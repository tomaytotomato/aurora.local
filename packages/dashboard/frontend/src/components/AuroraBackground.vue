<script setup lang="ts">
import { computed } from 'vue';
import { pickAuroraForToday, type AuroraPhoto } from '@/lib/aurora-photos';

interface Props {
  /** Override the photo (default: deterministic per-day pick). */
  photo?: AuroraPhoto;
  /** Intensity of the dark scrim. Higher = more readable content over top. */
  scrim?: 'soft' | 'medium' | 'strong';
}

const props = withDefaults(defineProps<Props>(), {
  scrim: 'medium',
});

const chosen = computed<AuroraPhoto>(() => props.photo ?? pickAuroraForToday());
const src = computed(() => `/aurora/${chosen.value.slot}.jpg`);
const scrimOpacity = computed(() => ({ soft: 0.35, medium: 0.55, strong: 0.7 }[props.scrim]));
</script>

<template>
  <!-- Fixed background layer. Sits at z-index: 0; all app content should
       be at z-index >= 1. Ignores pointer events except on the credit bubble. -->
  <!-- Photo layer only. The attribution is a separate <AuroraCredit>
       footer that each layout renders at the bottom of its page flow, so
       the credit sits at the bottom of the page rather than floating over
       the fixed background. -->
  <div class="aurora-bg" aria-hidden="true">
    <img :src="src" :alt="''" class="photo" />
    <div class="scrim" :style="{ '--scrim-a': scrimOpacity }" />
  </div>
</template>

<style scoped>
.aurora-bg {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  overflow: hidden;
  background: #14120f;
}

.aurora-bg .photo {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  /* Gentle Ken-Burns-ish drift so the still image doesn't feel dead.
     Very slow, respects reduced-motion. */
  animation: aurora-drift 60s ease-in-out infinite alternate;
}

.aurora-bg .scrim {
  position: absolute;
  inset: 0;
  background:
    /* Warm vignette from the corners so the photo doesn't blow out edges. */
    radial-gradient(
      ellipse at center,
      rgba(0, 0, 0, 0) 30%,
      rgba(20, 18, 15, calc(var(--scrim-a) * 0.6)) 100%
    ),
    /* Base scrim to keep foreground panels legible. */
    rgba(20, 18, 15, var(--scrim-a));
}

@keyframes aurora-drift {
  0%   { transform: scale(1.06) translate(0, 0); }
  100% { transform: scale(1.10) translate(-1.5%, -1%); }
}

@media (prefers-reduced-motion: reduce) {
  .aurora-bg .photo { animation: none; }
}
</style>
