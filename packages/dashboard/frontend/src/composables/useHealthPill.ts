import { computed, type ComputedRef } from 'vue';
import { usePackagesStore } from '@/stores/packages';

/**
 * Aurora dashboard health-pill composable.
 *
 * iter-3 V3: the health pill was previously computed inside DashboardHome
 * and rendered in the Packages card. TopBar's centre region was left
 * intentionally empty in iter-2 because lifting this into a shared
 * surface was out of budget. Now shared so both DashboardHome and
 * TopBar consume the same source of truth without re-fetching packages.
 *
 * Notes:
 *   - Composable, not a Pinia store — the underlying data (packages
 *     store) is already global. This is a derived read-only view.
 *   - `usePackagesStore()` inside a composable is safe: the store is
 *     created on first access and shared afterwards.
 *   - Degraded state will land with BL1 (media sub-checklist).
 */

export type HealthState = 'running' | 'partial' | 'needs-config' | 'failed' | 'not-started';

export interface HealthPill {
  text: string;
  tone: 'ok' | 'warn' | 'err' | 'neutral';
  state: HealthState;
}

export function useHealthPill(): { pill: ComputedRef<HealthPill> } {
  const packages = usePackagesStore();

  const state = computed<HealthState>(() => {
    const xs = packages.enabled;
    if (xs.length === 0) return 'not-started';
    const running = xs.filter((p) => p.running).length;
    if (running === xs.length) return 'running';
    // Honesty fix: some-but-not-all running used to report 'not-started',
    // so 4-of-5-up read "Not started" in the TopBar on every page. Report
    // the partial state instead. (`failed`/`needs-config` stay reserved
    // for when the wire carries a per-package reason; nothing sets them
    // from booleans alone.)
    if (running === 0) return 'not-started';
    return 'partial';
  });

  const pill = computed<HealthPill>(() => {
    switch (state.value) {
      // Scoped to apps on purpose: this pill is computed only from
      // package running-state, not from security, disks, or backup. A
      // bare "All good" read as a global verdict and could sit green in
      // the header while the page below flagged open security findings.
      // Naming it "Apps: all running" keeps it honest about what it
      // actually knows.
      case 'running': return { text: 'Apps: all running', tone: 'ok', state: state.value };
      case 'partial': return { text: 'Apps: partly running', tone: 'warn', state: state.value };
      case 'failed':  return { text: 'Apps: attention needed', tone: 'err', state: state.value };
      case 'needs-config': return { text: 'Apps: need setup', tone: 'warn', state: state.value };
      default:        return { text: 'Apps: not started', tone: 'neutral', state: state.value };
    }
  });

  return { pill };
}
