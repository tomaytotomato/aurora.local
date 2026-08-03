<script setup lang="ts">
import Sidebar from './Sidebar.vue';
import TopBar from './TopBar.vue';
import AuroraBackground from '@/components/AuroraBackground.vue';
import { computed } from 'vue';
import { useRoute } from 'vue-router';

// iter-3 V1: routes can opt into the aurora photo background via
// `meta: { photoBg: true }` on their router entry. Keeps existing
// opaque chrome (sidebar + topbar keep their warm surface bg) and
// lets the photo peek around content edges and beneath the fold —
// same idea as OnboardingShell, adjusted for the two-column app grid.
const route = useRoute();
const photoBg = computed<boolean>(() => Boolean(route.meta?.photoBg));
</script>

<template>
  <AuroraBackground v-if="photoBg" scrim="strong" />

  <div
    class="min-h-screen grid grid-cols-[240px_1fr]"
    :class="photoBg ? 'relative z-10' : 'bg-background'"
  >
    <Sidebar />
    <div class="flex flex-col min-h-screen">
      <TopBar />
      <main class="flex-1 anim-enter">
        <div class="content py-10">
          <router-view v-slot="{ Component }">
            <transition name="fade" mode="out-in">
              <component :is="Component" />
            </transition>
          </router-view>
        </div>
      </main>
      <footer
        class="content py-6 text-xs border-t mt-8"
        :class="photoBg ? 'text-white/70 border-white/15' : 'text-muted-foreground border-border/60'"
      >
        Aurora — admin plane for aurora.local. The tile grid is
        <a href="/" :class="photoBg ? 'text-white/85' : 'text-muted-foreground'">Homepage</a>; this is the fuse box.
      </footer>
    </div>
  </div>
</template>

<style scoped>
.fade-enter-active, .fade-leave-active {
  transition: opacity 180ms ease, transform 180ms ease;
}
.fade-enter-from { opacity: 0; transform: translateY(4px); }
.fade-leave-to { opacity: 0; transform: translateY(-4px); }
</style>
