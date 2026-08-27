// Core-service catalogue for /apps/core.
//
// The Core page used to render one card for the whole `core` package,
// even though `core` ships three long-running containers (Caddy, Authelia,
// Stalwart) and rides alongside a fourth that _is_ the dashboard itself
// (Aurora). Users kept asking "why is only Caddy shown?" because the icon
// on the single card is caddy.svg. Fair.
//
// Rather than break the package model (core is genuinely one unit for
// install/uninstall/upgrade), this file defines the human view: one card
// per user-facing service. Kept as a static list because:
//
//   1. The set is stable — these four services are what "core" means.
//      A new one lands rarely and by intentional change, not by a scan
//      of running containers picking up whatever is there.
//   2. The order matters for storytelling (Caddy is the front door;
//      Aurora is the dashboard; Authelia is the gate; Stalwart is mail),
//      and a scan cannot know that ordering.
//   3. It lets the "disabled" copy for Aurora sit right next to the
//      thing it describes rather than getting synthesised in the view.
//
// Backing container name and package name are kept separate on purpose:
// `container` matches docker's `--name`, which is what /api/containers
// returns; `package` is which manifest that container belongs to, which
// matters only for the "you cannot uninstall this" story.

export interface CoreService {
  /** Route slug: /apps/core/services/<key>. Stable, safe in URLs. */
  key: string;
  /** Display label, sentence case. */
  label: string;
  /** One-line description shown on the card. */
  description: string;
  /**
   * Icon slug under {@code /icons/<slug>.svg}. Every entry has one — no
   * fallback path — because the whole point of this page is that each
   * service is a real thing with a face.
   */
  icon: string;
  /** Container name docker + /api/containers use. */
  container: string;
  /**
   * Which package owns this container. `dashboard` is the infrastructure
   * package (Aurora itself); every other entry is part of `core`.
   */
  package: 'core' | 'dashboard';
  /**
   * URL template for the service's own user-facing UI. Rendered as the
   * "Open <service>" CTA in the detail hero — the same shape marketplace
   * apps get from their manifest vhosts (see PackageDetail.openUrl). The
   * literal token {@code {domain}} is substituted with the current
   * .state.yml domain at render time so a hostname change (or a
   * different box on the same repo) doesn't leave a stale link.
   *
   * <p>Omitted entirely when the service has no interactive web UI —
   * Caddy publishes an admin API on :2019 but doesn't expose it outside
   * the compose network, and hiding the button reads better than
   * disabling it. Same reasoning marketplace apps without vhosts use.
   */
  openUrl?: string;
  /**
   * Label for the Open CTA. Defaults to `Open <label>` when omitted.
   * Overrides let us say "Open Aurora SSO" instead of "Open Authelia"
   * where the product name we picked (Aurora SSO) diverges from the
   * container name (authelia).
   */
  openLabel?: string;
  /**
   * When set, the card is non-clickable and the copy explains why.
   * Reserved for services whose "details" surface is meaningless because
   * you are already looking at them (Aurora → this dashboard).
   */
  disabled?: {
    /** Short reason, shown as a badge on the card. */
    badge: string;
    /** Prose explaining where the operator should go instead. */
    hint: string;
  };
}

/**
 * The order here is the order on the page:
 *   Caddy    — the reverse proxy, the front door on :80/:443
 *   Aurora   — this dashboard (disabled card, pointer to Settings)
 *   Authelia — the SSO gate, the source of the OTPs the wizard surfaces
 *   Stalwart — the mail server, so Aurora can send + receive on the box
 */
export const CORE_SERVICES: readonly CoreService[] = [
  {
    key: 'caddy',
    label: 'Caddy',
    description:
      'The reverse proxy. Fans traffic on :80 and :443 out to every other service on subdomains under your domain.',
    icon: 'caddy',
    container: 'caddy',
    package: 'core',
    // No openUrl on purpose: Caddy's admin API sits on :2019 inside the
    // compose network, unpublished, and there is no browser UI on the
    // other end. Rendering a dead link reads worse than not rendering
    // one at all.
  },
  {
    key: 'aurora',
    label: 'Aurora',
    description: 'The dashboard itself — install, configure, monitor, and troubleshoot every app on the box.',
    icon: 'aurora',
    container: 'aurora',
    package: 'dashboard',
    disabled: {
      badge: 'this dashboard',
      hint: 'This is what powers the dashboard you are looking at. Go to Settings to make changes to it.',
    },
  },
  {
    key: 'authelia',
    label: 'Authelia',
    description: 'Aurora SSO and second-factor gate for every app. Emits the enrollment links and one-time codes shown below.',
    icon: 'authelia',
    container: 'authelia',
    package: 'core',
    // The sign-in portal, where operators enrol a passkey / TOTP and
    // reset their own password. Same URL the wizard hands out on the
    // "Set up SSO" step — the two paths agree.
    openUrl: 'https://auth.{domain}/',
    openLabel: 'Open Aurora SSO',
  },
  {
    key: 'stalwart',
    label: 'Stalwart',
    description: 'The box\u2019s mail server \u2014 SMTP, IMAP, JMAP. Ships in core so the system can send and receive mail from day one.',
    icon: 'stalwart',
    container: 'stalwart',
    package: 'core',
    // Stalwart's HTTP surface: setup wizard, admin console, JMAP.
    // Served on :8080 inside the compose net; Caddy fronts it at
    // mail-admin behind Authelia (admins only). Matches
    // packages/core/manifest.yml post_install_notes.
    openUrl: 'https://mail-admin.{domain}/',
    openLabel: 'Open mail admin',
  },
] as const;

/** Look up a service by its route slug. Returns undefined for unknown slugs. */
export function findCoreService(key: string): CoreService | undefined {
  return CORE_SERVICES.find((s) => s.key === key);
}

/**
 * Resolve {@link CoreService.openUrl} against the current domain.
 *
 * <p>Returns null when the service has no template, when the container
 * is not running (a link to an unreachable UI just 502s under Caddy),
 * or when the domain is not yet known — the wizard sets it during
 * onboarding and rendering the hero before that would emit
 * {@code https://mail-admin.//}. Same shape marketplace apps use for
 * their Open CTA (see PackageDetail.openUrl).
 */
export function resolveOpenUrl(
  service: CoreService,
  containerRunning: boolean,
  domain: string | null | undefined,
): string | null {
  if (!service.openUrl) return null;
  if (!containerRunning) return null;
  if (!domain) return null;
  return service.openUrl.replace('{domain}', domain);
}
