<script setup lang="ts">
import { useAuthStore } from '@/stores/auth';
import { useSystemStore } from '@/stores/system';
import { usePackagesStore } from '@/stores/packages';
import { renderIdentity } from '@/lib/identity';
import { useTheme } from '@/composables/useTheme';
import { useHealthPill } from '@/composables/useHealthPill';
import Badge from '@/components/ui/Badge.vue';
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
const packages = usePackagesStore();
const router = useRouter();
const { theme, toggle: toggleTheme } = useTheme();
const { pill: healthPill } = useHealthPill();

// UX_SPEC_DASHBOARD.md §3.1 + D3/D4/D10 + iter-3 B2: hostname/domain source
// of truth is .state.yml (via /api/system). Delegated to lib/identity.ts
// which encodes the dedup rule (avoid `aurora.aurora.local` when the
// hostname is already the leading label of the domain).
const hostLabel = computed(() =>
  renderIdentity(system.info?.hostname, system.info?.domain),
);

onMounted(() => {
  if (!system.info) system.fetchInfo().catch(() => { /* silent */ });
  // iter-3 V3: TopBar owns the aggregate health pill in its centre region,
  // so it also owns the fetch trigger. DashboardHome will also fetch on
  // mount, but if the user lands on any other authenticated view first,
  // the pill still resolves to real data instead of "Not started".
  if (packages.list.length === 0) packages.fetchList().catch(() => { /* silent */ });
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

      <!-- iter-3 V3: aggregate health pill lives here. Was intentionally
           empty in iter-2 because `healthPill` was still trapped inside
           DashboardHome. Now shared via `composables/useHealthPill.ts`. -->
      <div class="justify-self-center" data-region="health">
        <Badge
          v-if="auth.session?.authenticated"
          :tone="healthPill.tone"
          :data-test="'topbar-health-pill'"
          :data-state="healthPill.state"
        >
          {{ healthPill.text }}
        </Badge>
      </div>

      <div
        class="flex items-center gap-2 justify-self-end text-xs text-ink-3"
        data-region="user"
      >
        <!--
          iter-3 V2: theme toggle. Renders as sun (light → click to go
          dark) or moon (dark → click to go light). Sits before the
          username/interpunct/Sign-out cluster so it can be reached
          without hunting near the page edge.
        -->
        <button
          type="button"
          class="p-1.5 rounded-md hover:text-ink hover:bg-surface-2/60 transition-colors"
          :aria-label="theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'"
          :aria-pressed="theme === 'dark'"
          data-test="theme-toggle"
          :data-theme-current="theme"
          @click="toggleTheme"
        >
          <svg v-if="theme === 'dark'" viewBox="0 0 24 24" class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <circle cx="12" cy="12" r="4" />
            <path d="M12 3v1.5M12 19.5V21M3 12h1.5M19.5 12H21M5.6 5.6l1.06 1.06M17.34 17.34l1.06 1.06M5.6 18.4l1.06-1.06M17.34 6.66l1.06-1.06" />
          </svg>
          <svg v-else viewBox="0 0 24 24" class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M20.5 14.5A8 8 0 019.5 3.5a8 8 0 1011 11z" />
          </svg>
        </button>
        <span
          v-if="auth.session?.username || auth.session?.authenticated"
          class="text-ink-4"
          aria-hidden="true"
        >·</span>
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
