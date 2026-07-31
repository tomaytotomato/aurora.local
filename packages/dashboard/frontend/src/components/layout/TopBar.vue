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

const hostLabel = computed(() => {
  if (!system.info) return 'aurora.local';
  return `${system.info.hostname}.${system.info.domain}`;
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
        <div class="font-mono text-xs text-ink-3">{{ hostLabel }}</div>
        <Badge :tone="events.connected ? 'ok' : 'neutral'">
          {{ events.connected ? 'live' : 'idle' }}
        </Badge>
      </div>

      <div class="flex items-center gap-4">
        <a href="/" class="text-xs text-ink-3">Back to Homepage</a>
        <span class="text-ink-4">·</span>
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
