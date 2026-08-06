<script setup lang="ts">
import Sidebar from './Sidebar.vue';
import TopBar from './TopBar.vue';
import AuroraBackground from '@/components/AuroraBackground.vue';

// The aurora photo background is now a constant across every app page,
// not a per-route opt-in. It is Aurora's signature; showing it only on
// Overview made the rest of the app feel like a different product. The
// opaque chrome (sidebar + topbar) and every Card keep their own solid
// surface, so the photo only shows through the gutters and behind
// outside-card headers. Those headers carry the `.on-photo` class so
// their text stays legible over the photo in either theme. See
// docs/STYLEGUIDE.md.
</script>

<template>
  <AuroraBackground scrim="strong" />

  <div class="min-h-screen grid grid-cols-[240px_1fr] relative z-10">
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
