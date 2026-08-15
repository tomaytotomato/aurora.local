<script setup lang="ts">
import Sidebar from './Sidebar.vue';
import TopBar from './TopBar.vue';
import AuroraBackground from '@/components/AuroraBackground.vue';
import AuroraCredit from '@/components/AuroraCredit.vue';

// The aurora photo background is now a constant across every app page,
// not a per-route opt-in. It is Aurora's signature; showing it only on
// Overview made the rest of the app feel like a different product. The
// opaque chrome (sidebar + topbar) and every Card keep their own solid
// surface, so the photo only shows through the gutters and behind
// outside-card headers. Those headers carry the `.on-photo` class so
// their text stays legible over the photo in either theme. See
// docs/STYLEGUIDE.md.
//
// Responsive (tablet fix): the fixed 240px sidebar column used to apply
// at every width, so a 768px portrait tablet lost a third of its width
// to the rail before any content rendered. Below `lg` (1024px — the
// same "sidebar becomes a top bar" idea OnboardingShell.vue already
// uses at 900px) the grid drops to a single column and Sidebar.vue
// switches itself to a horizontal top bar. At `lg` and up (desktop, and
// tablet landscape at/above the base 1024px iPad) the grid — and
// Sidebar — look exactly as before.
</script>

<template>
  <AuroraBackground scrim="strong" />

  <div class="min-h-screen grid grid-cols-1 lg:grid-cols-[240px_1fr] relative z-10">
    <Sidebar />
    <div class="flex flex-col min-h-screen">
      <TopBar />
      <main class="flex-1 flex flex-col anim-enter">
        <div class="content py-10 flex-1">
          <router-view v-slot="{ Component }">
            <transition name="fade" mode="out-in">
              <component :is="Component" />
            </transition>
          </router-view>
        </div>
        <!-- Photo attribution at the foot of the page (scrolls with it). -->
        <AuroraCredit class="content pb-6" />
      </main>
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
