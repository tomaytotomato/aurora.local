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
import type { BackupPolicy, BackupSource, BackupStatus, Snapshot } from '@/api/backup';
import type { Disk, Parity, Pool } from '@/api/disks';
import type { JobStatus } from '@/api/jobs';
import type { PackageNetwork, VhostProtection } from '@/api/network';
import type { NotificationChannel, NotificationDelivery } from '@/api/notifications';
import type { OnboardingDraft } from '@/api/onboarding';
import type { PackageUpdate } from '@/api/updates';
import type { OpenVpnClient, OpenVpnConfig, VpnConfig, VpnPeer } from '@/api/vpn';
import type { User } from '@/api/users';
import { jobScript, type JobScript } from './fixtures/jobs';
import {
  initialOpenVpnClients,
  initialOpenVpnConfig,
  initialPeers,
  initialVpnConfig,
} from './fixtures/vpn';
import { initialUpdates } from './fixtures/updates';
import {
  initialPolicy,
  initialSnapshots,
  initialSources,
  initialStatus,
} from './fixtures/backup';
import { initialDisks, initialParity, initialPool } from './fixtures/disks';
import { initialChannels, initialDeliveries } from './fixtures/notifications';
import { initialProtection } from './fixtures/network';
import { CURRENT_USER_ID, initialUsers } from './fixtures/users';

/**
 * A job plus the script it is playing out. `cursor` is how many of the
 * script's lines have already been emitted, so a stream that reconnects
 * mid-job replays the tail it missed and then carries on.
 */
export interface MockJob extends JobStatus {
  script: JobScript;
  cursor: number;
}

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
  /** Long-running operations, keyed by job id. Grows as actions are taken. */
  jobs: Record<string, MockJob>;
  /** Per-package update availability, keyed by package name. */
  updates: Record<string, PackageUpdate>;
  /** Kopia repository state, what it protects, and its history. */
  backup: {
    status: BackupStatus;
    sources: BackupSource[];
    snapshots: Snapshot[];
    policy: BackupPolicy;
  };
  /** Physical drives, the mergerfs pool, and SnapRAID parity. */
  disks: {
    disks: Disk[];
    pool: Pool;
    parity: Parity;
  };
  /** Where Aurora sends word when something happens, and what it sent. */
  notifications: {
    channels: NotificationChannel[];
    deliveries: NotificationDelivery[];
  };
  /** Egress mode per app (populated on first change), and edge protection per vhost. */
  network: {
    byPackage: Record<string, PackageNetwork>;
    protection: VhostProtection[];
  };
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
    enabled_packages: ['core', 'identity', 'storage', 'privacy', 'media', 'monitoring'],
    dns_mode: 'adguard',
  },
  // identity (Authelia) and storage (Samba) are core/essential, so they're
  // always on alongside core.
  enabled: new Set(['core', 'identity', 'storage', 'privacy', 'media', 'monitoring']),
  running: new Set(['core', 'identity', 'storage', 'privacy', 'monitoring']), // media enabled but not yet up
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
  jobs: initialJobs(),
  updates: initialUpdates(),
  backup: {
    status: initialStatus(),
    sources: initialSources(),
    snapshots: initialSnapshots(),
    policy: initialPolicy(),
  },
  disks: {
    disks: initialDisks(),
    pool: initialPool(),
    parity: initialParity(),
  },
  notifications: {
    channels: initialChannels(),
    deliveries: initialDeliveries(),
  },
  network: {
    byPackage: {},
    protection: initialProtection(),
  },
};

/**
 * One already-finished job so the "last update failed" link on the AI
 * card leads to a real log rather than a 404. Its id matches
 * `lastUpdateJobId` in the updates fixture.
 */
function initialJobs(): Record<string, MockJob> {
  const script = jobScript('update', 'ai');
  const failed: MockJob = {
    id: 'job-update-ai-prior',
    kind: 'update',
    target: 'ai',
    state: 'failed',
    startedAt: '2026-08-07T21:04:11Z',
    finishedAt: '2026-08-07T21:04:58Z',
    exitCode: script.exitCode,
    failureCode: script.failureCode,
    failureReason: script.failureReason,
    tail: [...script.lines],
    script,
    cursor: script.lines.length,
  };
  return { [failed.id]: failed };
}
