<script setup lang="ts">
import { computed } from 'vue';
import { pickAuroraForToday, type AuroraPhoto } from '@/lib/aurora-photos';

// Attribution for the aurora photo (CC BY-SA — must stay visible wherever
// the photo shows). Rendered by each layout at the FOOT of its page flow,
// not inside the fixed background: this sits at the bottom of the page and
// scrolls with it, rather than floating over the viewport.
//
// Uses the same deterministic per-day pick as AuroraBackground, so the
// credit always matches the photo on screen without prop-passing.
const chosen = computed<AuroraPhoto>(() => pickAuroraForToday());
</script>

<template>
  <footer class="aurora-credit-row">
    <a
      class="aurora-credit"
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
  </footer>
</template>

<style scoped>
.aurora-credit-row {
  display: flex;
  justify-content: flex-end;
}

.aurora-credit {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  background: rgba(20, 18, 15, 0.55);
  backdrop-filter: blur(12px) saturate(140%);
  -webkit-backdrop-filter: blur(12px) saturate(140%);
  color: rgba(255, 255, 255, 0.85);
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 999px;
  font-size: 11px;
  font-weight: 500;
  line-height: 1;
  text-decoration: none;
  max-width: 100%;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: background 160ms ease, color 160ms ease;
}

.aurora-credit:hover {
  background: rgba(20, 18, 15, 0.78);
  color: #fff;
}

.aurora-credit:focus-visible {
  outline: 2px solid rgba(255, 255, 255, 0.75);
  outline-offset: 2px;
}

.aurora-credit svg { flex-shrink: 0; opacity: 0.8; }
.aurora-credit .sep { opacity: 0.5; padding: 0 1px; }
.aurora-credit .photographer { opacity: 0.85; }

@media (max-width: 520px) {
  .aurora-credit .sep,
  .aurora-credit .photographer { display: none; }
}
</style>
