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
  <div class="aurora-bg" aria-hidden="true">
    <img :src="src" :alt="''" class="photo" />
    <div class="scrim" :style="{ '--scrim-a': scrimOpacity }" />
  </div>

  <!-- Bing-style credit bubble, anchored to the viewport (not the photo).
       Rendered outside the aria-hidden background so it stays accessible. -->
  <a
    class="credit"
    :href="chosen.sourceUrl"
    target="_blank"
    rel="noopener noreferrer"
    :title="`${chosen.location} · ${chosen.photographer} · ${chosen.license}`"
    :aria-label="`Aurora photo: ${chosen.location}, by ${chosen.photographer}, licensed ${chosen.license}. Opens Wikimedia source page.`"
  >
    <svg viewBox="0 0 24 24" width="12" height="12" aria-hidden="true">
      <path
        d="M12 8v4l3 2M12 2a10 10 0 100 20 10 10 0 000-20z"
        fill="none"
        stroke="currentColor"
        stroke-width="1.75"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
    </svg>
    <span class="location">{{ chosen.location }}</span>
    <span class="sep" aria-hidden="true">·</span>
    <span class="photographer">{{ chosen.photographer }}</span>
  </a>
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

/* -- Credit bubble ------------------------------------------------------- */

.credit {
  position: fixed;
  right: 20px;
  bottom: 20px;
  z-index: 20;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 13px;
  background: rgba(20, 18, 15, 0.55);
  backdrop-filter: blur(12px) saturate(140%);
  -webkit-backdrop-filter: blur(12px) saturate(140%);
  color: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(255, 255, 255, 0.15);
  border-radius: 999px;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.01em;
  line-height: 1;
  text-decoration: none;
  transition: background 160ms ease, color 160ms ease, transform 160ms ease;
  max-width: calc(100vw - 40px);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.credit:hover {
  background: rgba(20, 18, 15, 0.78);
  color: #fff;
}

.credit:focus-visible {
  outline: 2px solid rgba(255, 255, 255, 0.75);
  outline-offset: 2px;
}

.credit svg { flex-shrink: 0; opacity: 0.8; }
.credit .sep { opacity: 0.5; padding: 0 1px; }
.credit .photographer { opacity: 0.85; }

@media (max-width: 520px) {
  .credit .sep,
  .credit .photographer { display: none; }
}
</style>
