// The "needs attention" aggregation behind the strip at the top of
// Overview.
//
// Aurora already detects everything in here — a failing drive, a backup
// that stopped, parity going stale, an open security finding, an update
// waiting — and until now each one lived on its own page. Someone who
// does not visit /disks does not learn their drive is reallocating
// sectors, which rather defeats the point of watching for it.
//
// Deliberately pure and deliberately not a component: the rules for what
// counts as urgent are worth testing without mounting anything, and this
// is the one place that decides.

import type { PackageUpdate } from '@/api/updates';
import type { SecurityFinding } from '@/api/security';
import type { BackupPolicy, BackupStatus } from '@/api/backup';
import type { Disk, Parity, Pool } from '@/api/disks';
import type { SystemInfo } from '@/api/system';

import { backupHeadline, backupPageState, daysSinceLastRun } from '@/api/backup';
import { countAvailable } from '@/api/updates';
import {
  diskAttention,
  disksNeedingAttention,
  fullBranches,
  parityFreshness,
  parityHeadline,
} from '@/api/disks';
import { safePercent } from '@/lib/utils';

export type AttentionTone = 'err' | 'warn' | 'info';

export interface AttentionItem {
  /** Stable key, so a row does not re-animate on every poll. */
  id: string;
  tone: AttentionTone;
  /** One sentence, already human. No counts without a noun. */
  text: string;
  /** Where to go to do something about it. */
  to: string;
  cta: string;
}

export interface AttentionInput {
  updates?: PackageUpdate[];
  findings?: SecurityFinding[];
  backup?: { status: BackupStatus; policy: Pick<BackupPolicy, 'stalenessWarnDays'> } | null;
  disks?: { disks: Disk[]; pool: Pool; parity: Parity } | null;
  system?: SystemInfo | null;
  /**
   * The hosted marketplace catalogue, when a verified newer index is
   * waiting for the operator to accept. Only the two fields the nudge
   * needs, so the whole MarketplaceStatus type does not have to be
   * imported into this pure module.
   */
  marketplace?: { updateAvailable: boolean; newAppCount?: number | null } | null;
  /** Root filesystem percentage above which Aurora starts saying so. */
  diskWarnPct?: number;
  nowMs?: number;
}

const TONE_RANK: Record<AttentionTone, number> = { err: 0, warn: 1, info: 2 };

/**
 * Everything currently worth a person's attention, most serious first.
 *
 * Returns an empty array on a clean box, and the strip renders nothing
 * at all rather than a green "all good" banner — a permanent reassurance
 * badge is noise that trains people to stop reading the row.
 */
export function buildAttention(input: AttentionInput): AttentionItem[] {
  const now = input.nowMs ?? Date.now();
  const items: AttentionItem[] = [];

  // ── Drives ──────────────────────────────────────────────────────────
  if (input.disks) {
    const { disks, pool, parity } = input.disks;

    for (const disk of disksNeedingAttention(disks)) {
      const reason = diskAttention(disk);
      if (!reason) continue;
      items.push({
        id: `disk:${disk.id}`,
        tone: disk.health === 'failing' ? 'err' : 'warn',
        text: `${disk.device} (${disk.model ?? 'unknown model'}) — ${reason.toLowerCase()}`,
        to: '/disks',
        cta: 'Disks',
      });
    }

    const freshness = parityFreshness(parity, now);
    if (freshness === 'failed' || freshness === 'aborted' || freshness === 'stale' || freshness === 'never') {
      items.push({
        id: 'parity',
        tone: freshness === 'failed' ? 'err' : 'warn',
        text: parityHeadline(parity, now),
        to: '/disks',
        cta: 'Disks',
      });
    }

    for (const branch of fullBranches(pool)) {
      items.push({
        id: `branch:${branch.path}`,
        tone: 'warn',
        text: `${branch.path} is full enough that nothing new is being written to it`,
        to: '/disks',
        cta: 'Disks',
      });
    }
  }

  // ── Backup ──────────────────────────────────────────────────────────
  if (input.backup) {
    const { status, policy } = input.backup;
    const state = backupPageState(status, policy, now);
    if (state !== 'healthy') {
      items.push({
        id: 'backup',
        tone: state === 'stale' || state === 'not-configured' ? 'warn' : 'err',
        text: backupHeadline(state, daysSinceLastRun(status, now)),
        to: '/backup',
        cta: 'Backup',
      });
    }
  }

  // ── Security ────────────────────────────────────────────────────────
  const findings = input.findings ?? [];
  const high = findings.filter((f) => f.severity === 'high');
  const rest = findings.filter((f) => f.severity !== 'high');
  if (high.length) {
    items.push({
      id: 'security:high',
      tone: 'err',
      text:
        high.length === 1
          ? high[0].title
          : `${high.length} serious security findings are open`,
      to: '/security',
      cta: 'Security',
    });
  }
  if (rest.length) {
    items.push({
      id: 'security:rest',
      tone: 'warn',
      text: `${rest.length} other security finding${rest.length === 1 ? '' : 's'} to look at`,
      to: '/security',
      cta: 'Security',
    });
  }

  // ── Root disk headroom ──────────────────────────────────────────────
  // Separate from the pool: the pool filling is inconvenient, the root
  // filesystem filling stops Docker dead.
  const warnPct = input.diskWarnPct ?? 90;
  const rootPct = safePercent(input.system?.diskUsedBytes, input.system?.diskTotalBytes);
  if (rootPct !== null && rootPct >= warnPct) {
    items.push({
      id: 'root-disk',
      tone: rootPct >= 95 ? 'err' : 'warn',
      text: `The system disk is ${rootPct}% full — Docker stops working when it fills`,
      to: '/disks',
      cta: 'Disks',
    });
  }

  // ── Updates ─────────────────────────────────────────────────────────
  const updateCount = countAvailable(input.updates ?? []);
  if (updateCount > 0) {
    items.push({
      id: 'updates',
      tone: 'info',
      text: `${updateCount} app${updateCount === 1 ? '' : 's'} ${updateCount === 1 ? 'has' : 'have'} an update waiting`,
      to: '/apps',
      cta: 'Apps',
    });
  }

  // ── Marketplace catalogue ───────────────────────────────────────────
  // A newer, signature-verified catalogue is waiting for the operator to
  // accept it (plan point 6). Info-tone: it is an opportunity, not a
  // problem, and accepting it never touches a running app (point 7).
  if (input.marketplace?.updateAvailable) {
    const n = input.marketplace.newAppCount ?? 0;
    const text =
      n > 0
        ? `The app marketplace has ${n} new app${n === 1 ? '' : 's'} to browse`
        : 'A newer app marketplace catalogue is ready to review';
    items.push({
      id: 'marketplace',
      tone: 'info',
      text,
      to: '/settings#marketplace',
      cta: 'Review',
    });
  }

  return items.sort((a, b) => TONE_RANK[a.tone] - TONE_RANK[b.tone]);
}

/** The worst tone present, for the strip's own badge. Null when clean. */
export function worstTone(items: AttentionItem[]): AttentionTone | null {
  if (!items.length) return null;
  return items.reduce<AttentionTone>(
    (worst, item) => (TONE_RANK[item.tone] < TONE_RANK[worst] ? item.tone : worst),
    'info',
  );
}
