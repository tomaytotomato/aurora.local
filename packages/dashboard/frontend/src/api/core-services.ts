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
  },
  {
    key: 'stalwart',
    label: 'Stalwart',
    description: 'The box\u2019s mail server \u2014 SMTP, IMAP, JMAP. Ships in core so the system can send and receive mail from day one.',
    icon: 'stalwart',
    container: 'stalwart',
    package: 'core',
  },
] as const;

/** Look up a service by its route slug. Returns undefined for unknown slugs. */
export function findCoreService(key: string): CoreService | undefined {
  return CORE_SERVICES.find((s) => s.key === key);
}
