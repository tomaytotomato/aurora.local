// Pure state-to-actions and state-to-status-light logic for the app
// detail page's control panel. Kept out of PackageDetail.vue so the
// matrix (the part most likely to regress when a new state shows up)
// can be tested without mounting the view.
//
// Backing verbs — all four now have a real backend endpoint:
//   install   -> POST /packages/{name}/enable   (enrol + start)
//   start     -> POST /services/{package}/start (start an already-enrolled,
//                stopped package; untouched by this change)
//   disable   -> POST /packages/{name}/stop     (stop only, stays enrolled
//                — so a plain Start brings it back with no reinstall)
//   uninstall -> POST /packages/{name}/disable  (stop + un-enrol; its own
//                doc summary is "Stop and disable a package". Data under
//                data/<name> is preserved either way.)
import type { ServiceState } from '@/api/services';

export type PackageAction = 'install' | 'start' | 'disable' | 'uninstall';

export interface ActionSlot {
  action: PackageAction;
  /** Rendered at all. False means the action makes no sense in this
   * state (e.g. Install for an already-installed app) — absent, not
   * disabled, per the styleguide's state vocabulary. */
  visible: boolean;
  /** Clickable. Only meaningful when visible is true. */
  enabled: boolean;
  /** Human-readable reason shown alongside a visible-but-disabled action. */
  reason?: string;
}

export interface ActionInputs {
  /** Core packages (core/identity/storage) can do none of these — see
   * isCorePackage() in api/packages.ts. */
  isCore: boolean;
  enabled: boolean;
  running: boolean;
}

const CORE_REASON = "This app runs the platform baseline and can't be added, started, stopped, or removed from here.";

/**
 * The four lifecycle actions for the current state, in a fixed order
 * (install, start, disable, uninstall) so callers can render a stable
 * layout. Filter on `visible` for what actually appears; `enabled` +
 * `reason` describe a visible-but-blocked action.
 */
export function packageActionSlots(input: ActionInputs): ActionSlot[] {
  const { isCore, enabled, running } = input;

  if (isCore) {
    return [
      { action: 'install', visible: false, enabled: false, reason: CORE_REASON },
      { action: 'start', visible: false, enabled: false, reason: CORE_REASON },
      { action: 'disable', visible: false, enabled: false, reason: CORE_REASON },
      { action: 'uninstall', visible: false, enabled: false, reason: CORE_REASON },
    ];
  }

  const notInstalled = !enabled;
  const stopped = enabled && !running;
  const isRunning = enabled && running;

  return [
    { action: 'install', visible: notInstalled, enabled: notInstalled },
    { action: 'start', visible: stopped, enabled: stopped },
    // Only visible while running — stopping an already-stopped package
    // is not a state the backend accepts (409), so there is nothing
    // useful for the button to do outside this state.
    { action: 'disable', visible: isRunning, enabled: isRunning },
    // Uninstall works from either installed state — disable() already
    // stops a running package as part of removing it (its own openapi.yaml
    // summary: "Stop and disable a package"), so there's no need to force
    // a Disable-then-Uninstall two-step the backend doesn't require.
    { action: 'uninstall', visible: enabled, enabled: enabled },
  ];
}

export type StatusLightState =
  | 'running'
  | 'stopped'
  | 'starting'
  | 'unhealthy'
  | 'not-installed'
  | 'unknown';

export interface LightInputs {
  /** False until the initial GET /packages/{name} has resolved. */
  loaded: boolean;
  enabled: boolean;
  running: boolean;
  /** Live probe state from /services/status, when available. Undefined
   * when the probe stream hasn't delivered yet, or the package isn't in
   * its scope (see StatusProbeService — it only probes packages listed
   * in .state.yml's enabled[]). */
  probeState?: ServiceState;
}

/**
 * Map the package's known state to one of six lights. Deliberately
 * conservative: `unknown` is a real, representable answer for "the
 * detail fetch hasn't resolved yet" rather than a fabricated green or
 * red guess. Once `detail` has loaded, `enabled`/`running` alone are
 * always enough to say running/stopped/not-installed — the probe only
 * adds the finer starting/unhealthy distinction on top when it's
 * available.
 */
export function deriveStatusLight(input: LightInputs): StatusLightState {
  if (!input.loaded) return 'unknown';
  if (!input.enabled) return 'not-installed';

  switch (input.probeState) {
    case 'running':
      return 'running';
    case 'starting':
      return 'starting';
    case 'failed':
    case 'needs-config':
      return 'unhealthy';
    case 'not-started':
      // Enabled but the probe says nothing is up — trust the plain
      // boolean over the probe's own optimism/pessimism here, since
      // running already reflects a live docker-compose label check.
      return input.running ? 'running' : 'stopped';
    default:
      // No probe data at all (stream not delivered yet, or this package
      // isn't in StatusProbeService's scope). Fall back to the boolean
      // we do have rather than showing unknown for a state we can
      // already answer.
      return input.running ? 'running' : 'stopped';
  }
}
