// Mutable in-memory state for the mock layer. Handlers read and write
// this so the app behaves like a real stateful backend within a session:
// PATCH /onboarding updates the draft, enabling a package flips it to
// running, and so on. A page reload resets everything (no persistence —
// this is a dev aid, not a fake database).
//
// EDIT ME to change the starting point. The two knobs you'll reach for
// most:
//   • onboarding.complete / bootstrap_mode / step — flip to develop the
//     wizard (see the note below).
//   • session.authenticated — set false to land on /login.

import type { Session } from '@/api/auth';
import type { OnboardingDraft } from '@/api/onboarding';
import type { OpenVpnClient, OpenVpnConfig, VpnConfig, VpnPeer } from '@/api/vpn';
import type { User } from '@/api/users';
import {
  initialOpenVpnClients,
  initialOpenVpnConfig,
  initialPeers,
  initialVpnConfig,
} from './fixtures/vpn';
import { CURRENT_USER_ID, initialUsers } from './fixtures/users';

export interface MockState {
  session: Session;
  onboarding: OnboardingDraft;
  /** Package names the operator has enabled. Drives derived running state. */
  enabled: Set<string>;
  /** Package names currently running (subset of enabled). */
  running: Set<string>;
  /** Inbound WireGuard/OpenVPN server state. Mutated by the vpn handlers. */
  vpn: {
    /** null = not-configured (before POST /vpn/config/init). */
    config: VpnConfig | null;
    peers: VpnPeer[];
    openVpn: OpenVpnConfig;
    openVpnClients: OpenVpnClient[];
  };
  /** Admin users. The row whose id === currentUserId is "you". */
  users: User[];
  currentUserId: string;
}

// Default: onboarding DONE, admin logged in. Every dashboard screen and
// (because the onboarding routes are public) every wizard step is
// reachable.
//
// To develop the wizard flow from a fresh box instead, set:
//   complete: false, bootstrap_mode: true, step: 'welcome'
// and the router guard will hold you inside /onboarding/**.
export const state: MockState = {
  session: {
    authenticated: true,
    username: 'admin',
    passkeyEnrolled: false,
    tz: 'Europe/London',
  },
  onboarding: {
    complete: true,
    bootstrap_mode: false,
    step: 'done',
    admin_username: 'admin',
    domain: 'aurora.local',
    enabled_packages: ['core', 'privacy', 'media', 'monitoring'],
    dns_mode: 'adguard',
  },
  enabled: new Set(['core', 'privacy', 'media', 'monitoring']),
  running: new Set(['core', 'privacy', 'monitoring']), // media enabled but not yet up
  vpn: {
    // Configured by default so the VPN page lands on its ready state. To
    // exercise the not-configured empty state, set this to null.
    config: initialVpnConfig(),
    peers: initialPeers(),
    openVpn: initialOpenVpnConfig(),
    openVpnClients: initialOpenVpnClients(),
  },
  users: initialUsers(),
  currentUserId: CURRENT_USER_ID,
};
