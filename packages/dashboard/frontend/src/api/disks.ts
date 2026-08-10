// Physical disks, the mergerfs pool, and SnapRAID parity. See
// docs/DISKS_PAGE_DESIGN.md.
//
// The host roles for all three landed before any of this was visible in
// the dashboard, which is the wrong way round: a disk gives weeks of
// warning before it dies, and you only get that warning if something is
// looking at it.

import { http } from './client';
import type { JobRef } from './packages';

export type SmartHealth = 'passed' | 'warning' | 'failing' | 'unknown';
export type DiskRole = 'data' | 'parity' | 'system' | 'unassigned';

export interface Disk {
  /** Stable id, by-id where available so it survives a re-cable. */
  id: string;
  device: string;
  model: string | null;
  serial: string | null;
  sizeBytes: number;
  role: DiskRole;
  mountpoint: string | null;
  filesystem: string | null;
  usedBytes: number | null;
  /** What smartctl reports overall. Famously optimistic; see `attentionReason`. */
  health: SmartHealth;
  temperatureC: number | null;
  powerOnHours: number | null;
  reallocatedSectors: number | null;
  pendingSectors: number | null;
  lastSelfTestAt: string | null;
  lastSelfTestResult: string | null;
}

export interface SmartAttribute {
  id: number;
  name: string;
  value: number;
  worst: number;
  threshold: number;
  raw: string;
  /** smartctl's own when-failed column: null when it never has. */
  failedWhen: string | null;
}

export interface DiskSmart {
  diskId: string;
  supported: boolean;
  overall: SmartHealth;
  attributes: SmartAttribute[];
  collectedAt: string;
}

export interface PoolBranch {
  diskId: string;
  path: string;
  totalBytes: number;
  usedBytes: number;
}

export interface Pool {
  configured: boolean;
  mountpoint: string | null;
  totalBytes: number | null;
  usedBytes: number | null;
  branches: PoolBranch[];
  /** mergerfs create policy, e.g. 'mfs' (most free space). */
  createPolicy: string | null;
  /** minfreespace, below which mergerfs stops choosing a branch. */
  minFreeBytes: number | null;
}

export type ParityRunState = 'ok' | 'failed' | 'aborted' | 'never';

export interface Parity {
  configured: boolean;
  parityDiskIds: string[];
  lastSyncAt: string | null;
  lastSyncState: ParityRunState;
  lastScrubAt: string | null;
  /** Files added or changed since the last sync — unprotected until the next one. */
  pendingChanges: number | null;
  /** Files deleted since the last sync. Compared against the runner's guard. */
  deletedSinceSync: number | null;
  /** snapraid_delete_threshold from the host role; the runner aborts above this. */
  deletionThreshold: number | null;
  /** Days after which Aurora calls parity stale. */
  stalenessWarnDays: number;
}

export type DisksPageState = 'no-pool' | 'healthy' | 'attention' | 'failing';

/**
 * Why a disk deserves attention, or null when it does not.
 *
 * SMART overall status is optimistic by design: manufacturers set the
 * thresholds and a drive can be steadily reallocating sectors while
 * still reporting PASSED. Any non-zero reallocated or pending count is
 * treated as a warning here for that reason.
 */
/**
 * Whether a smartctl self-test result is bad news.
 *
 * Note the "without error" exclusion: the overwhelmingly common healthy
 * result is the string "Completed without error", which contains the
 * word "error" and so matches any naive search for one. An "Interrupted
 * (host reset)" is not counted either — that is a reboot mid-test, not a
 * fault.
 */
function selfTestFailed(result: string | null): boolean {
  if (!result) return false;
  if (/without error/i.test(result)) return false;
  return /(fail|error|aborted)/i.test(result);
}

export function diskAttention(disk: Disk): string | null {
  if (disk.health === 'failing') return 'SMART reports this drive as failing';
  if (selfTestFailed(disk.lastSelfTestResult)) {
    return 'The last self-test did not complete cleanly';
  }
  if ((disk.reallocatedSectors ?? 0) > 0) {
    return `${disk.reallocatedSectors} reallocated sector${disk.reallocatedSectors === 1 ? '' : 's'}`;
  }
  if ((disk.pendingSectors ?? 0) > 0) {
    return `${disk.pendingSectors} sector${disk.pendingSectors === 1 ? '' : 's'} pending reallocation`;
  }
  if (disk.health === 'warning') return 'SMART reports a warning';
  if ((disk.temperatureC ?? 0) > 55) return `Running at ${disk.temperatureC}°C`;
  return null;
}

/** Badge tone for a disk, honouring the reallocated-sector rule above. */
export function diskTone(disk: Disk): 'ok' | 'warn' | 'err' | 'neutral' {
  if (disk.health === 'failing') return 'err';
  if (disk.health === 'unknown') return 'neutral';
  return diskAttention(disk) === null ? 'ok' : 'warn';
}

/** Disks worth looking at, worst first. */
export function disksNeedingAttention(disks: Disk[]): Disk[] {
  const rank = (d: Disk): number => {
    if (d.health === 'failing') return 0;
    if (diskAttention(d) !== null) return 1;
    return 2;
  };
  return disks.filter((d) => diskAttention(d) !== null).sort((a, b) => rank(a) - rank(b));
}

/** Sort for the Drives table: worst health first, then by device name. */
export function sortByHealth(disks: Disk[]): Disk[] {
  const rank = (d: Disk): number => {
    if (d.health === 'failing') return 0;
    if (diskAttention(d) !== null) return 1;
    if (d.health === 'unknown') return 2;
    return 3;
  };
  return [...disks].sort((a, b) => rank(a) - rank(b) || a.device.localeCompare(b.device));
}

/** Whole days since the last successful parity sync. Null when never. */
export function daysSinceSync(parity: Parity, nowMs = Date.now()): number | null {
  if (!parity.lastSyncAt || parity.lastSyncState === 'never') return null;
  const then = Date.parse(parity.lastSyncAt);
  if (!Number.isFinite(then)) return null;
  return Math.max(0, Math.floor((nowMs - then) / 86_400_000));
}

export type ParityFreshness = 'not-configured' | 'never' | 'aborted' | 'failed' | 'stale' | 'fresh';

/**
 * How much to trust parity right now. `aborted` is separate from
 * `failed` because it is not a fault: the runner refuses to sync when
 * deletions exceed the threshold, which is a deliberate guard against
 * one bad `rm -rf` destroying the ability to recover. It still means
 * parity is not current, and it still needs a person.
 */
export function parityFreshness(parity: Parity, nowMs = Date.now()): ParityFreshness {
  if (!parity.configured) return 'not-configured';
  if (parity.lastSyncState === 'aborted') return 'aborted';
  if (parity.lastSyncState === 'failed') return 'failed';
  if (parity.lastSyncState === 'never' || !parity.lastSyncAt) return 'never';
  const days = daysSinceSync(parity, nowMs);
  if (days === null) return 'never';
  return days >= parity.stalenessWarnDays ? 'stale' : 'fresh';
}

export function parityTone(freshness: ParityFreshness): 'ok' | 'warn' | 'err' | 'neutral' {
  switch (freshness) {
    case 'fresh':
      return 'ok';
    case 'stale':
    case 'aborted':
      return 'warn';
    case 'failed':
      return 'err';
    default:
      return 'neutral';
  }
}

/** One sentence about parity, for the header and the Overview card. */
export function parityHeadline(parity: Parity, nowMs = Date.now()): string {
  const freshness = parityFreshness(parity, nowMs);
  const days = daysSinceSync(parity, nowMs);
  switch (freshness) {
    case 'not-configured':
      return 'No parity disk configured';
    case 'never':
      return 'Parity has never been synced';
    case 'aborted':
      return `Sync stopped itself: ${parity.deletedSinceSync ?? 0} files deleted, over the ${parity.deletionThreshold ?? 0} allowed`;
    case 'failed':
      return 'The last parity sync failed';
    case 'stale':
      return `Parity is ${days} day${days === 1 ? '' : 's'} old`;
    case 'fresh':
      return days === 0 ? 'Parity synced today' : `Parity synced ${days} day${days === 1 ? '' : 's'} ago`;
  }
}

export function poolUsedPct(pool: Pool): number | null {
  if (pool.totalBytes === null || pool.usedBytes === null || pool.totalBytes <= 0) return null;
  return Math.max(0, Math.min(100, Math.round((pool.usedBytes / pool.totalBytes) * 100)));
}

export function branchUsedPct(branch: PoolBranch): number {
  if (branch.totalBytes <= 0) return 0;
  return Math.max(0, Math.min(100, Math.round((branch.usedBytes / branch.totalBytes) * 100)));
}

/**
 * Branches with less free space than mergerfs's `minfreespace`. These
 * are the disks it has quietly stopped writing to, which is why a pool
 * percentage on its own is misleading.
 */
export function fullBranches(pool: Pool): PoolBranch[] {
  const floor = pool.minFreeBytes;
  if (floor === null) return [];
  return pool.branches.filter((b) => b.totalBytes - b.usedBytes < floor);
}

export function disksPageState(disks: Disk[], pool: Pool, parity: Parity, nowMs = Date.now()): DisksPageState {
  if (disks.some((d) => d.health === 'failing')) return 'failing';
  const freshness = parityFreshness(parity, nowMs);
  if (freshness === 'failed') return 'failing';
  if (!pool.configured) return 'no-pool';
  if (disksNeedingAttention(disks).length > 0) return 'attention';
  if (freshness === 'stale' || freshness === 'aborted' || freshness === 'never') return 'attention';
  if (fullBranches(pool).length > 0) return 'attention';
  return 'healthy';
}

export const DisksApi = {
  async list(): Promise<Disk[]> {
    const { data } = await http.get<Disk[]>('/disks');
    return data;
  },
  async smart(id: string): Promise<DiskSmart> {
    const { data } = await http.get<DiskSmart>(`/disks/${encodeURIComponent(id)}/smart`);
    return data;
  },
  async pool(): Promise<Pool> {
    const { data } = await http.get<Pool>('/disks/pool');
    return data;
  },
  async parity(): Promise<Parity> {
    const { data } = await http.get<Parity>('/disks/parity');
    return data;
  },
  /** Bring parity up to date with the current contents. Returns a job. */
  async sync(): Promise<JobRef> {
    const { data } = await http.post<JobRef>('/disks/parity/sync');
    return data;
  },
  /** Verify a portion of existing parity against the data. Returns a job. */
  async scrub(): Promise<JobRef> {
    const { data } = await http.post<JobRef>('/disks/parity/scrub');
    return data;
  },
};
