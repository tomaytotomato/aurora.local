<script setup lang="ts">
import { RouterLink, useRoute } from 'vue-router';
import { computed, onMounted, ref } from 'vue';
import { useSystemStore } from '@/stores/system';
import { useAuthStore } from '@/stores/auth';
import { SecurityApi } from '@/api/security';
import {
  countBySeverity,
  totalCount,
  highestSeverityTone,
  documentTitleWithFindings,
  EMPTY_COUNTS,
  type SeverityCounts,
  type SeverityTone,
} from '@/lib/severity';

const route = useRoute();
const system = useSystemStore();
const auth = useAuthStore();

interface NavItem {
  to: string;
  label: string;
  icon: string; // svg path 'd'
  requiresCapability?: keyof (import('@/api/system').SystemCapabilities);
  /** Phase D iter-10: sidebar links can gate on the session role. */
  requiresRole?: 'admin' | 'user' | 'guest';
  /** iter-32: optional badge count computed at render time. */
  badgeKey?: 'security';
}

// iter-3 P1b: `/security` is gated behind capabilities.securityScanner.
// Until M4 lands the capability is false and the nav entry hides so
// Sarah can't route into what is honestly a placeholder page.
const nav: NavItem[] = [
  { to: '/', label: 'Overview', icon: 'M3 12 L12 3 L21 12 M5 10 V21 H19 V10' },
  { to: '/packages', label: 'Packages', icon: 'M3 7 L12 3 L21 7 L12 11 Z M3 7 V17 L12 21 M21 7 V17 L12 21' },
  { to: '/security', label: 'Security', icon: 'M12 3 L20 6 V12 C20 17 16 20 12 21 C8 20 4 17 4 12 V6 Z', requiresCapability: 'securityScanner', badgeKey: 'security' },
  // Phase D iter-10 (D9): Users management — admin-only. Hidden from
  // regular USER + GUEST sessions so it's not even in the tab order.
  { to: '/users', label: 'Users', icon: 'M12 12 A4 4 0 1 1 12 4 A4 4 0 0 1 12 12 M4 21 V19 A5 5 0 0 1 9 14 H15 A5 5 0 0 1 20 19 V21', requiresRole: 'admin' },
  { to: '/settings', label: 'Settings', icon: 'M12 8 A4 4 0 1 1 12 16 A4 4 0 1 1 12 8 M12 2 V4 M12 20 V22 M4 12 H2 M22 12 H20 M5 5 L6.5 6.5 M17.5 17.5 L19 19 M5 19 L6.5 17.5 M17.5 6.5 L19 5' },
];

const visibleNav = computed<NavItem[]>(() =>
  nav.filter((item) => {
    if (item.requiresCapability &&
        system.info?.capabilities?.[item.requiresCapability] !== true) {
      // Fetch may still be pending on first mount — hide by default.
      return false;
    }
    if (item.requiresRole && auth.session?.role !== item.requiresRole) {
      return false;
    }
    return true;
  }),
);

const isActive = (to: string): boolean => {
  if (to === '/') return route.path === '/';
  return route.path.startsWith(to);
};

// iter-32: aggregate security-findings counts for the sidebar nudge.
// Refreshes on mount + whenever the route lands on /security so a
// dismiss / restore action updates the badge on return. Silent on
// failure — the badge just stays hidden.
const securityCounts = ref<SeverityCounts>({ ...EMPTY_COUNTS });

async function refreshSecurityCounts(): Promise<void> {
  try {
    const list = await SecurityApi.findings();
    securityCounts.value = countBySeverity(list);
  } catch {
    securityCounts.value = { ...EMPTY_COUNTS };
  }
}

function totalSecurity(): number { return totalCount(securityCounts.value); }
function highestSeverityToneRow(): SeverityTone { return highestSeverityTone(securityCounts.value); }

onMounted(async () => {
  if (!system.info) {
    try { await system.fetchInfo(); } catch { /* silent */ }
  }
  if (system.info?.capabilities?.securityScanner === true) {
    void refreshSecurityCounts();
  }
});

// iter-33: document.title prefix with severity glyph + count. Renders
// in the browser tab preview so an operator working elsewhere sees
// 'Aurora • 3 issues' before switching tabs. Kept minimal — the glyph
// carries the severity so screen-only text works too. Removes the
// prefix when nothing is open.
function updateDocumentTitle(): void {
  document.title = documentTitleWithFindings(securityCounts.value);
}

// Update whenever the counts change.
watch(securityCounts, () => { updateDocumentTitle(); }, { deep: true });

import { onScopeDispose } from 'vue';
onScopeDispose(() => {
  // Restore the plain title when the sidebar tears down (route change
  // out of AppShell, e.g. into onboarding or /login).
  document.title = 'Aurora';
});

// Refresh when the user leaves /security so a dismissed finding
// updates the badge on the way out.
import { watch } from 'vue';
watch(() => route.path, (path, prev) => {
  if (system.info?.capabilities?.securityScanner !== true) return;
  // Refresh only when transitioning AWAY from /security — that's the
  // window when the counts most likely changed.
  if (prev && prev.startsWith('/security') && !path.startsWith('/security')) {
    void refreshSecurityCounts();
  }
});
</script>

<template>
  <aside class="border-r border-border/60 bg-card flex flex-col">
    <div class="px-6 py-5 border-b border-border/60">
      <RouterLink to="/" class="flex items-center gap-2.5 no-underline">
        <svg viewBox="0 0 32 32" class="w-6 h-6" aria-hidden="true">
          <!-- iter-3 theme-flip: bg uses --color-ink (dark in light mode,
               near-white in dark mode); stroke + dot use the inverting
               --color-on-ink token so the A-glyph always contrasts. -->
          <rect width="32" height="32" rx="6" fill="var(--color-foreground)"/>
          <path d="M8 22 L16 8 L24 22" stroke="var(--color-primary-foreground)" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
          <circle cx="16" cy="22" r="1.5" fill="var(--color-accent)"/>
        </svg>
        <span class="font-serif text-lg leading-none text-foreground">Aurora</span>
      </RouterLink>
      <div class="mt-1 eyebrow">admin plane</div>
    </div>

    <nav class="flex-1 py-4 px-3">
      <RouterLink
        v-for="item in visibleNav"
        :key="item.to"
        :to="item.to"
        class="flex items-center gap-3 px-3 py-2 rounded-md text-sm no-underline transition-colors duration-150"
        :class="isActive(item.to)
          ? 'bg-muted text-foreground'
          : 'text-muted-foreground hover:text-foreground hover:bg-muted/60'"
      >
        <svg viewBox="0 0 24 24" class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="1.5">
          <path :d="item.icon" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <span class="flex-1">{{ item.label }}</span>
        <!--
          iter-32 sidebar badge for /security nudge. Only rendered when
          the section has open findings so a clean box shows no chrome.
          Tone maps to the highest severity present.
        -->
        <span
          v-if="item.badgeKey === 'security' && totalSecurity() > 0"
          class="inline-flex items-center justify-center min-w-[1.25rem] px-1.5 py-0.5 rounded-full text-[0.6875rem] font-medium tabular-nums"
          :class="{
            'bg-destructive/10 text-destructive': highestSeverityToneRow() === 'err',
            'bg-warning/10 text-warning': highestSeverityToneRow() === 'warn',
            'bg-info/10 text-info': highestSeverityToneRow() === 'info',
          }"
          data-test="sidebar-security-badge"
          :aria-label="totalSecurity() + ' open security findings'"
        >{{ totalSecurity() }}</span>
      </RouterLink>
    </nav>

    <div class="px-6 py-5 border-t border-border/60">
      <div class="eyebrow mb-2">Documentation</div>
      <a href="/docs/DASHBOARD_BRIEF.md" class="text-xs text-muted-foreground">Brief</a>
      <span class="mx-2 text-muted-foreground">·</span>
      <a href="/docs/PACKAGE_CONTRACT.md" class="text-xs text-muted-foreground">Packages</a>
    </div>
  </aside>
</template>
