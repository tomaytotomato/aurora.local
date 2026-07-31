<script setup lang="ts">
import { RouterLink, useRoute } from 'vue-router';
import { computed } from 'vue';

const route = useRoute();

interface NavItem {
  to: string;
  label: string;
  icon: string; // svg path 'd'
}

const nav: NavItem[] = [
  { to: '/', label: 'Overview', icon: 'M3 12 L12 3 L21 12 M5 10 V21 H19 V10' },
  { to: '/packages', label: 'Packages', icon: 'M3 7 L12 3 L21 7 L12 11 Z M3 7 V17 L12 21 M21 7 V17 L12 21' },
  { to: '/security', label: 'Security', icon: 'M12 3 L20 6 V12 C20 17 16 20 12 21 C8 20 4 17 4 12 V6 Z' },
  { to: '/settings', label: 'Settings', icon: 'M12 8 A4 4 0 1 1 12 16 A4 4 0 1 1 12 8 M12 2 V4 M12 20 V22 M4 12 H2 M22 12 H20 M5 5 L6.5 6.5 M17.5 17.5 L19 19 M5 19 L6.5 17.5 M17.5 6.5 L19 5' },
];

const isActive = (to: string): boolean => {
  if (to === '/') return route.path === '/';
  return route.path.startsWith(to);
};
</script>

<template>
  <aside class="border-r border-line/60 bg-surface flex flex-col">
    <div class="px-6 py-5 border-b border-line/60">
      <RouterLink to="/" class="flex items-center gap-2.5 no-underline">
        <svg viewBox="0 0 32 32" class="w-6 h-6">
          <rect width="32" height="32" rx="6" fill="var(--color-ink)"/>
          <path d="M8 22 L16 8 L24 22" stroke="#FAF9F6" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
          <circle cx="16" cy="22" r="1.5" fill="var(--color-accent)"/>
        </svg>
        <span class="font-serif text-lg leading-none text-ink">Aurora</span>
      </RouterLink>
      <div class="mt-1 eyebrow">admin plane</div>
    </div>

    <nav class="flex-1 py-4 px-3">
      <RouterLink
        v-for="item in nav"
        :key="item.to"
        :to="item.to"
        class="flex items-center gap-3 px-3 py-2 rounded-md text-sm no-underline transition-colors duration-150"
        :class="isActive(item.to)
          ? 'bg-surface-2 text-ink'
          : 'text-ink-3 hover:text-ink hover:bg-surface-2/60'"
      >
        <svg viewBox="0 0 24 24" class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="1.5">
          <path :d="item.icon" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        {{ item.label }}
      </RouterLink>
    </nav>

    <div class="px-6 py-5 border-t border-line/60">
      <div class="eyebrow mb-2">Documentation</div>
      <a href="/docs/DASHBOARD_BRIEF.md" class="text-xs text-ink-3">Brief</a>
      <span class="mx-2 text-ink-4">·</span>
      <a href="/docs/PACKAGE_CONTRACT.md" class="text-xs text-ink-3">Packages</a>
    </div>
  </aside>
</template>
