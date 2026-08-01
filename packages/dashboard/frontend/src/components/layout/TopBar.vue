<script setup lang="ts">
import { useAuthStore } from '@/stores/auth';
import { useSystemStore } from '@/stores/system';
import { useEventsStore } from '@/stores/events';
import { computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import Badge from '@/components/ui/Badge.vue';

const auth = useAuthStore();
const system = useSystemStore();
const events = useEventsStore();
const router = useRouter();

// UX_SPEC_DASHBOARD.md §3.1 + D3/D4/D10: hostname/domain source of truth is
// .state.yml (via /api/system). Never render `undefined`; never emit a
// bare trailing dot. Falls back to "aurora.local" only when we have no
// data at all — an em-dash is used per-half when one side is missing.
const hostLabel = computed(() => {
  const info = system.info;
  if (!info) return 'aurora.local';
  const h = info.hostname ?? null;
  const d = info.domain ?? null;
  if (!h && !d) return 'aurora.local';
  if (!h) return `\u2014.${d}`;
  if (!d) return `${h}.\u2014`;
  return `${h}.${d}`;
});

onMounted(() => {
  if (!system.info) system.fetchInfo().catch(() => { /* silent */ });
});

async function signOut(): Promise<void> {
  await auth.logout();
  router.push('/login');
}
</script>

<template>
  <header class="border-b border-line/60 bg-canvas">
    <div class="content h-14 flex items-center justify-between">
      <div class="flex items-center gap-4">
        <div class="font-mono text-xs text-ink-3" data-test="topbar-identity">{{ hostLabel }}</div>
        <Badge :tone="events.connected ? 'ok' : 'neutral'">
          {{ events.connected ? 'live' : 'idle' }}
        </Badge>
      </div>

      <div class="flex items-center gap-4">
        <!--
          UX_SPEC_DASHBOARD.md D5: `Back to Homepage` removed. Homepage was
          retired in v0.1 (see packages/core/compose.yml). The dashboard IS
          the home.
        -->
        <span class="text-xs text-ink-3" v-if="auth.session?.username">
          {{ auth.session.username }}
        </span>
        <button
          v-if="auth.session?.authenticated"
          type="button"
          class="text-xs text-ink-3 hover:text-ink"
          @click="signOut"
        >
          Sign out
        </button>
      </div>
    </div>
  </header>
</template>
