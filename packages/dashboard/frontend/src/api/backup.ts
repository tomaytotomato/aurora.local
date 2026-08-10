// Backup state, read from Kopia via the backend rather than from Kopia's
// own web UI on port 51515. See docs/BACKUP_PAGE_DESIGN.md.
//
// Aurora answers one question here: is my data safe, and how do I get it
// back? Repository management and the snapshot browser stay in Kopia,
// which already does them well.

import { http } from './client';
import type { JobRef } from './packages';

export type RepoState = 'not-configured' | 'connected' | 'unreachable';
export type SnapshotState = 'ok' | 'partial' | 'failed';

/** What has to happen before a path can be snapshotted consistently. */
export interface BackupAction {
  kind: 'postgres-dump' | 'mysql-dump' | 'sqlite-backup' | 'command';
  description: string;
  /** Container the action runs in, when it runs in one. */
  container: string | null;
}

export interface BackupStatus {
  repoState: RepoState;
  /** 'filesystem', 's3', 'b2', 'sftp'… as Kopia reports it. */
  repoKind: string | null;
  repoLocation: string | null;
  encrypted: boolean;
  /** Logical size of everything protected. */
  totalSizeBytes: number | null;
  /** What it actually occupies after dedup and compression. */
  uniqueSizeBytes: number | null;
  snapshotCount: number;
  lastRunAt: string | null;
  lastRunState: SnapshotState | null;
  lastRunDurationMs: number | null;
  nextRunAt: string | null;
  generatedAt: string;
}

export interface BackupSource {
  id: string;
  path: string;
  /** The aurora package this path belongs to, when Aurora can tell. */
  package: string | null;
  enabled: boolean;
  lastSnapshotAt: string | null;
  lastSnapshotState: SnapshotState | null;
  sizeBytes: number | null;
  fileCount: number | null;
  beforeActions: BackupAction[];
  /**
   * True when this source contains a database Aurora knows about and no
   * before-action is declared for it. A Postgres data directory copied
   * while the server is running is not a backup, so this is surfaced as
   * a warning rather than left to be discovered during a restore.
   */
  needsConsistencyAction: boolean;
}

export interface Snapshot {
  id: string;
  sourceId: string;
  path: string;
  createdAt: string;
  state: SnapshotState;
  sizeBytes: number;
  fileCount: number;
  /** Taken with the source's before-actions applied. */
  consistent: boolean;
}

export interface BackupPolicy {
  scheduleCron: string;
  /** The cron expression in English, rendered server-side. */
  scheduleLabel: string;
  keepDaily: number;
  keepWeekly: number;
  keepMonthly: number;
  /** Days without a successful snapshot before Aurora calls it stale. */
  stalenessWarnDays: number;
}

/** Whole-page state, derived rather than sent, so one rule decides it. */
export type BackupPageState =
  | 'not-configured'
  | 'unreachable'
  | 'failed'
  | 'stale'
  | 'healthy';

/** Whole days since the last successful run. Null when there never was one. */
export function daysSinceLastRun(
  status: Pick<BackupStatus, 'lastRunAt' | 'lastRunState'>,
  nowMs = Date.now(),
): number | null {
  if (!status.lastRunAt || status.lastRunState === 'failed') return null;
  const then = Date.parse(status.lastRunAt);
  if (!Number.isFinite(then)) return null;
  return Math.max(0, Math.floor((nowMs - then) / 86_400_000));
}

/**
 * One rule for what the page and the Home tile are looking at. `failed`
 * and `stale` are deliberately separate: a run that failed loudly last
 * night is a different problem from a schedule that stopped three weeks
 * ago, and the second one is the one that loses data.
 */
export function backupPageState(
  status: BackupStatus,
  policy: Pick<BackupPolicy, 'stalenessWarnDays'>,
  nowMs = Date.now(),
): BackupPageState {
  if (status.repoState === 'not-configured') return 'not-configured';
  if (status.repoState === 'unreachable') return 'unreachable';
  if (status.lastRunState === 'failed') return 'failed';
  const days = daysSinceLastRun(status, nowMs);
  if (days === null) return 'stale';
  return days >= policy.stalenessWarnDays ? 'stale' : 'healthy';
}

/** Badge tone for a page state, in the app's existing vocabulary. */
export function backupTone(state: BackupPageState): 'ok' | 'warn' | 'err' | 'neutral' {
  switch (state) {
    case 'healthy':
      return 'ok';
    case 'stale':
      return 'warn';
    case 'failed':
    case 'unreachable':
      return 'err';
    default:
      return 'neutral';
  }
}

/** One short sentence for the Home tile and the page header. */
export function backupHeadline(state: BackupPageState, days: number | null): string {
  switch (state) {
    case 'not-configured':
      return 'No backup repository yet';
    case 'unreachable':
      return "Aurora can't reach the backup repository";
    case 'failed':
      return 'The last backup failed';
    case 'stale':
      return days === null
        ? 'Nothing has been backed up yet'
        : `No successful backup in ${days} day${days === 1 ? '' : 's'}`;
    case 'healthy':
      if (days === null) return 'Backed up';
      if (days === 0) return 'Backed up today';
      return `Backed up ${days} day${days === 1 ? '' : 's'} ago`;
  }
}

/** Sources whose data would not restore cleanly. Drives the warning rows. */
export function sourcesAtRisk(sources: BackupSource[]): BackupSource[] {
  return sources.filter(
    (s) => s.enabled && (s.needsConsistencyAction || s.lastSnapshotState === 'failed'),
  );
}

/** Dedup ratio as a percentage saved, or null when either figure is missing. */
export function dedupSavingPct(status: Pick<BackupStatus, 'totalSizeBytes' | 'uniqueSizeBytes'>): number | null {
  const { totalSizeBytes: total, uniqueSizeBytes: unique } = status;
  if (total === null || unique === null || total <= 0 || unique < 0) return null;
  return Math.max(0, Math.min(100, Math.round((1 - unique / total) * 100)));
}

export const BackupApi = {
  async status(): Promise<BackupStatus> {
    const { data } = await http.get<BackupStatus>('/backup/status');
    return data;
  },
  async sources(): Promise<BackupSource[]> {
    const { data } = await http.get<BackupSource[]>('/backup/sources');
    return data;
  },
  async setSourceEnabled(id: string, enabled: boolean): Promise<BackupSource> {
    const { data } = await http.patch<BackupSource>(`/backup/sources/${encodeURIComponent(id)}`, { enabled });
    return data;
  },
  async snapshots(sourceId?: string): Promise<Snapshot[]> {
    const { data } = await http.get<Snapshot[]>('/backup/snapshots', {
      params: sourceId ? { sourceId } : {},
    });
    return data;
  },
  /** Back up now. Returns a job to stream. */
  async runNow(): Promise<JobRef> {
    const { data } = await http.post<JobRef>('/backup/snapshots');
    return data;
  },
  /** Restore a whole snapshot back to its original path. Returns a job. */
  async restore(snapshotId: string): Promise<JobRef> {
    const { data } = await http.post<JobRef>(`/backup/snapshots/${encodeURIComponent(snapshotId)}/restore`);
    return data;
  },
  async policy(): Promise<BackupPolicy> {
    const { data } = await http.get<BackupPolicy>('/backup/policy');
    return data;
  },
  async savePolicy(patch: Partial<BackupPolicy>): Promise<BackupPolicy> {
    const { data } = await http.put<BackupPolicy>('/backup/policy', patch);
    return data;
  },
};
