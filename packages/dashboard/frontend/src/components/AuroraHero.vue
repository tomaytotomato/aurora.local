<script setup lang="ts">
import { computed } from 'vue';
import { pickAuroraForToday, type AuroraPhoto } from '@/lib/aurora-photos';

interface Props {
  /** Override the photo (default: deterministic per-day pick). */
  photo?: AuroraPhoto;
  /** Heights: 'sm' 220px · 'md' 320px · 'lg' 420px. */
  height?: 'sm' | 'md' | 'lg';
  /** Softens the bottom into the page background. */
  fade?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  height: 'md',
  fade: true,
});

const chosen = computed<AuroraPhoto>(() => props.photo ?? pickAuroraForToday());
const heightPx = computed(() => ({ sm: '220px', md: '320px', lg: '420px' }[props.height]));
const src = computed(() => `/aurora/${chosen.value.slot}.jpg`);
</script>

<template>
  <figure class="hero" :style="{ height: heightPx }">
    <img :src="src" :alt="`Aurora borealis over ${chosen.location}`" loading="eager" />
    <div v-if="fade" class="fade" />

    <!-- Bing-style semi-transparent credit bubble, bottom-right. -->
    <a
      class="credit"
      :href="chosen.sourceUrl"
      target="_blank"
      rel="noopener noreferrer"
      :title="`${chosen.location} · ${chosen.photographer} · ${chosen.license}`"
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
  </figure>
</template>

<style scoped>
.hero {
  position: relative;
  width: 100%;
  overflow: hidden;
  border-radius: 12px;
  margin: 0;
  background: #14120f;   /* deep ink so slow-loading images don't flash white */
}

.hero img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

/* Fade the bottom edge into the page background so the hero feels
   integrated rather than pasted on. */
.fade {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: linear-gradient(
    to bottom,
    rgba(0, 0, 0, 0) 0%,
    rgba(0, 0, 0, 0) 55%,
    rgba(20, 18, 15, 0.15) 85%,
    rgba(20, 18, 15, 0.35) 100%
  );
}

/* Credit bubble — semi-transparent, backdrop blur, subtle border.
   Sits above everything, gently interactive. */
.credit {
  position: absolute;
  right: 12px;
  bottom: 12px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  background: rgba(20, 18, 15, 0.55);
  backdrop-filter: blur(10px) saturate(140%);
  -webkit-backdrop-filter: blur(10px) saturate(140%);
  color: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(255, 255, 255, 0.15);
  border-radius: 999px;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.01em;
  line-height: 1;
  text-decoration: none;
  transition: background 160ms ease, color 160ms ease, transform 160ms ease;
  max-width: calc(100% - 24px);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.credit:hover {
  background: rgba(20, 18, 15, 0.75);
  color: #fff;
}

.credit:focus-visible {
  outline: 2px solid rgba(255, 255, 255, 0.75);
  outline-offset: 2px;
}

.credit svg {
  flex-shrink: 0;
  opacity: 0.8;
}

.credit .sep {
  opacity: 0.5;
  padding: 0 1px;
}

.credit .photographer {
  opacity: 0.85;
}

/* Small screens: collapse to location only. */
@media (max-width: 520px) {
  .credit .sep,
  .credit .photographer {
    display: none;
  }
}
</style>
