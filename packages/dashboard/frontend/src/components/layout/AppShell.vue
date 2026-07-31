<script setup lang="ts">
import Sidebar from './Sidebar.vue';
import TopBar from './TopBar.vue';
</script>

<template>
  <div class="min-h-screen bg-canvas grid grid-cols-[240px_1fr]">
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
      <footer class="content py-6 text-xs text-ink-4 border-t border-line/60 mt-8">
        Aurora — admin plane for aurora.local. The tile grid is
        <a href="/" class="text-ink-3">Homepage</a>; this is the fuse box.
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
