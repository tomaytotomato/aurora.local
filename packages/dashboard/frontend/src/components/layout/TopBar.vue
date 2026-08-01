<script setup lang="ts">
import { useAuthStore } from '@/stores/auth';
import { useSystemStore } from '@/stores/system';
import { computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';

// iter-dash-polish-2 P2 (BLOCKER): header anatomy.
//   - `idle`/`live` badge removed — SSE connection state is developer
//     telemetry, not a header signal for Sarah.
//   - Three regions (identity | health | user) laid out on grid-cols-3
//     so the identity string can grow without colliding with user actions.
//   - Centre `health` region intentionally empty in iter-2: lifting the
//     aggregated health pill from DashboardHome into a shared store is
//     out-of-budget per the iter-2 plan's non-goal clause. Empty is
//     spec-compliant ("leaving the centre region empty is better than
//     leaving `idle` there").
//   - Username · Sign out separated by U+00B7 interpunct.

const auth = useAuthStore();
const system = useSystemStore();
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
    <div class="content h-14 grid grid-cols-3 items-center">
      <div
        class="font-mono text-xs text-ink-3 justify-self-start"
        data-test="topbar-identity"
        data-region="identity"
      >
        {{ hostLabel }}
      </div>

      <!-- Health pill region — intentionally empty in iter-2 (P2 fallback). -->
      <div class="justify-self-center" data-region="health"></div>

      <div
        class="flex items-center gap-2 justify-self-end text-xs text-ink-3"
        data-region="user"
      >
        <!--
          UX_SPEC_DASHBOARD.md D5: `Back to Homepage` removed. Homepage was
          retired in v0.1 (see packages/core/compose.yml). The dashboard IS
          the home.
        -->
        <span v-if="auth.session?.username">{{ auth.session.username }}</span>
        <span
          v-if="auth.session?.username && auth.session?.authenticated"
          class="text-ink-4"
          aria-hidden="true"
        >·</span>
        <button
          v-if="auth.session?.authenticated"
          type="button"
          class="hover:text-ink"
          @click="signOut"
        >
          Sign out
        </button>
      </div>
    </div>
  </header>
</template>
