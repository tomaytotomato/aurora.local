<script setup lang="ts">
import { useRoute } from 'vue-router';

// A secondary, in-page nav for a section with more than one real route
// (Apps: Catalogue / Core today). Deliberately NOT another Tabs strip —
// the owner explicitly disliked stacking two underline tab strips when
// Apps/Core sat above the All/Enabled/Available filter. This renders as
// a row of pills instead, so a page can still use Tabs once for its own
// content (e.g. Catalogue's Installed/Marketplace) without it reading
// as nested tabs. Sits directly on the aurora photo, so it carries its
// own light-on-dark colours rather than relying on the `.on-photo`
// cascade (which only targets specific tag types).
const props = defineProps<{
  items: readonly { to: string; label: string }[];
}>();

const route = useRoute();
function isActive(to: string): boolean {
  return route.path === to;
}
</script>

<template>
  <nav class="flex items-center gap-1" aria-label="Apps sections">
    <router-link
      v-for="item in props.items"
      :key="item.to"
      :to="item.to"
      class="px-3.5 py-1.5 rounded-full text-sm no-underline transition-colors duration-150"
      :class="isActive(item.to) ? 'bg-card text-foreground' : 'text-white/70 hover:text-white hover:bg-white/10'"
    >{{ item.label }}</router-link>
  </nav>
</template>
