<script setup lang="ts">
import { RouterLink, useRoute } from 'vue-router';
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
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
  { to: '/apps', label: 'Apps', icon: 'M3 7 L12 3 L21 7 L12 11 Z M3 7 V17 L12 21 M21 7 V17 L12 21' },
  { to: '/vpn', label: 'VPN', icon: 'M12 3 L4 6 V11 C4 16 7.5 19.5 12 21 C16.5 19.5 20 16 20 11 V6 Z M9 11.5 L11 13.5 L15 9.5' },
  { to: '/backup', label: 'Backup', icon: 'M4 7 C4 5.3 7.6 4 12 4 C16.4 4 20 5.3 20 7 M4 7 C4 8.7 7.6 10 12 10 C16.4 10 20 8.7 20 7 M4 7 V17 C4 18.7 7.6 20 12 20 C16.4 20 20 18.7 20 17 V7 M4 12 C4 13.7 7.6 15 12 15 C16.4 15 20 13.7 20 12', requiresCapability: 'backup' },
  { to: '/disks', label: 'Disks', icon: 'M3 6.5 A9 2.5 0 0 0 21 6.5 A9 2.5 0 0 0 3 6.5 M3 6.5 V17.5 A9 2.5 0 0 0 21 17.5 V6.5 M3 12 A9 2.5 0 0 0 21 12', requiresCapability: 'disks' },
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

/**
 * Below `lg` the nav is a horizontally scrolling strip, and it scrolled
 * with nothing to say so: Settings sits off the right edge on a tablet
 * and there was no cue that anything was there. A fade on the right edge
 * is that cue, shown only while there is genuinely more to scroll to, so
 * a viewport wide enough to fit every item gets no decoration it has not
 * earned.
 *
 * Measured rather than assumed via a media query, because whether the
 * strip overflows depends on how many items the session can actually see
 * (capability flags and the admin role both change the count).
 */
const navStrip = ref<HTMLElement | null>(null);
const moreToScroll = ref(false);

function measureNavOverflow(): void {
  const el = navStrip.value;
  if (!el) return;
  // 1px of slack: sub-pixel layout means scrollWidth can exceed
  // clientWidth by a fraction on a strip that is visually complete.
  moreToScroll.value = el.scrollWidth - el.clientWidth - el.scrollLeft > 1;
}

let navResizeObserver: ResizeObserver | null = null;
onMounted(() => {
  measureNavOverflow();
  if (typeof ResizeObserver !== 'undefined' && navStrip.value) {
    navResizeObserver = new ResizeObserver(measureNavOverflow);
    navResizeObserver.observe(navStrip.value);
  }
});
onBeforeUnmount(() => {
  navResizeObserver?.disconnect();
  navResizeObserver = null;
});

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
  <!--
    Responsive (tablet fix): below `lg` this renders as a horizontal top
    bar instead of a full-height vertical rail — AppShell.vue drops its
    grid to a single column at the same breakpoint, so there's no fixed-
    width column stealing space from page content on a portrait tablet.
    `lg:` classes below restore the original vertical-rail markup
    unchanged for desktop and tablet-landscape widths.
  -->
  <aside
    class="border-border/60 bg-card flex flex-col
           border-b lg:border-b-0 lg:border-r"
  >
    <div
      class="flex items-center justify-between gap-4 px-4 py-3 border-b border-border/60
             lg:block lg:px-6 lg:py-5"
    >
      <RouterLink to="/" class="flex items-center gap-2.5 no-underline shrink-0">
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
      <!-- Decorative kicker — hidden below `sm` to keep the collapsed top
           bar to one line on a phone-narrow viewport. Every tablet width
           in scope (>=768px) is above `sm` (640px) so it still shows. -->
      <div class="eyebrow hidden sm:block lg:mt-1">admin plane</div>
    </div>

    <!-- The fade is positioned against this wrapper, not the scroll
         container, so it stays put instead of scrolling away with the
         content it is describing. -->
    <div class="relative min-w-0 lg:flex-1 lg:flex lg:flex-col">
      <nav
        ref="navStrip"
        class="flex flex-row items-center gap-1 overflow-x-auto px-3 py-2
               lg:flex-1 lg:flex-col lg:items-stretch lg:gap-0 lg:overflow-visible lg:py-4"
        @scroll="measureNavOverflow"
      >
      <RouterLink
        v-for="item in visibleNav"
        :key="item.to"
        :to="item.to"
        class="flex items-center gap-2 px-3 py-2 rounded-md text-sm no-underline
               transition-colors duration-150 shrink-0 whitespace-nowrap
               lg:gap-3 lg:w-full lg:shrink lg:whitespace-normal"
        :class="isActive(item.to)
          ? 'bg-muted text-foreground'
          : 'text-muted-foreground hover:text-foreground hover:bg-muted/60'"
      >
        <svg viewBox="0 0 24 24" class="w-4 h-4 shrink-0" fill="none" stroke="currentColor" stroke-width="1.5">
          <path :d="item.icon" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <!-- Labels stay visible through the whole tablet range (>=640px);
             only a phone-narrow top bar drops to icon-only. -->
        <span class="hidden sm:inline lg:flex-1">{{ item.label }}</span>
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

      <!-- The affordance itself: a fade over the right edge of the strip,
           below `lg` only (the vertical rail does not scroll). aria-hidden
           and pointer-events-none — it is a hint to the eye, not content,
           and must not swallow a tap on the item underneath it. -->
      <div
        v-if="moreToScroll"
        class="pointer-events-none absolute right-0 top-0 bottom-0 w-10
               bg-gradient-to-l from-background to-transparent lg:hidden"
        aria-hidden="true"
        data-test="sidebar-scroll-affordance"
      />
    </div>

    <!-- Documentation footer is non-essential chrome; hidden below `lg`
         so the collapsed top bar stays compact on tablet. -->
    <div class="hidden lg:block px-6 py-5 border-t border-border/60">
      <div class="eyebrow mb-2">Documentation</div>
      <a href="/docs/DASHBOARD_BRIEF.md" class="text-xs text-muted-foreground">Brief</a>
      <span class="mx-2 text-muted-foreground">·</span>
      <a href="/docs/PACKAGE_CONTRACT.md" class="text-xs text-muted-foreground">Packages</a>
    </div>
  </aside>
</template>
