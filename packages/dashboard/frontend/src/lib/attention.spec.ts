import { describe, expect, it } from 'vitest';

import type { BackupStatus } from '@/api/backup';
import type { Disk, Parity, Pool } from '@/api/disks';
import type { SecurityFinding } from '@/api/security';
import type { SystemInfo } from '@/api/system';
import type { PackageUpdate } from '@/api/updates';

import { buildAttention, worstTone, type AttentionInput } from './attention';

const NOW = Date.parse('2026-08-08T12:00:00Z');
const DAY = 86_400_000;
const GIB = 1024 ** 3;

function disk(over: Partial<Disk> & { id: string }): Disk {
  return {
    device: `/dev/${over.id}`,
    model: 'A Drive',
    serial: 'X1',
    sizeBytes: 100 * GIB,
    role: 'data',
    mountpoint: '/mnt/disk1',
    filesystem: 'ext4',
    usedBytes: 10 * GIB,
    health: 'passed',
    temperatureC: 38,
    powerOnHours: 1000,
    reallocatedSectors: 0,
    pendingSectors: 0,
    lastSelfTestAt: null,
    lastSelfTestResult: 'Completed without error',
    ...over,
  };
}

function healthyDisks(): NonNullable<AttentionInput['disks']> {
  const pool: Pool = {
    configured: true,
    mountpoint: '/mnt/storage',
    totalBytes: 100 * GIB,
    usedBytes: 10 * GIB,
    branches: [{ diskId: 'a', path: '/mnt/disk1', totalBytes: 100 * GIB, usedBytes: 10 * GIB }],
    createPolicy: 'mfs',
    minFreeBytes: 20 * GIB,
  };
  const parity: Parity = {
    configured: true,
    parityDiskIds: ['p'],
    lastSyncAt: new Date(NOW - DAY).toISOString(),
    lastSyncState: 'ok',
    lastScrubAt: null,
    pendingChanges: 0,
    deletedSinceSync: 0,
    deletionThreshold: 200,
    stalenessWarnDays: 3,
  };
  return { disks: [disk({ id: 'sda' })], pool, parity };
}

function healthyBackup(): NonNullable<AttentionInput['backup']> {
  const status: BackupStatus = {
    repoState: 'connected',
    repoKind: 'filesystem',
    repoLocation: '/repository',
    encrypted: true,
    totalSizeBytes: 100,
    uniqueSizeBytes: 50,
    snapshotCount: 4,
    lastRunAt: new Date(NOW - 3_600_000).toISOString(),
    lastRunState: 'ok',
    lastRunDurationMs: 1000,
    nextRunAt: null,
    generatedAt: new Date(NOW).toISOString(),
  };
  return { status, policy: { stalenessWarnDays: 3 } };
}

function finding(over: Partial<SecurityFinding> & { id: string }): SecurityFinding {
  return { severity: 'medium', title: 'Something', description: '', remediationUrl: null, ...over };
}

function update(over: Partial<PackageUpdate> & { package: string }): PackageUpdate {
  return {
    state: 'current',
    images: [],
    lastCheckedAt: null,
    lastUpdatedAt: null,
    lastUpdateJobId: null,
    lastUpdateFailed: false,
    ...over,
  };
}

function system(over: Partial<SystemInfo> = {}): SystemInfo {
  return {
    hostname: 'aurora',
    domain: 'aurora.local',
    lanIp: null,
    distro: null,
    kernel: null,
    uptimeSeconds: 1,
    cpuCount: 4,
    memTotalBytes: 100,
    memUsedBytes: 10,
    diskTotalBytes: 100 * GIB,
    diskUsedBytes: 10 * GIB,
    dockerVersion: null,
    containerCount: 1,
    capabilities: { metrics: true },
    ...over,
  };
}

/** A box with nothing wrong with it. */
function clean(): AttentionInput {
  return {
    nowMs: NOW,
    disks: healthyDisks(),
    backup: healthyBackup(),
    findings: [],
    updates: [],
    system: system(),
  };
}

describe('buildAttention', () => {
  it('says nothing at all about a healthy box', () => {
    // Deliberate: a permanent "all good" banner is noise that trains
    // people to stop reading the row.
    expect(buildAttention(clean())).toEqual([]);
    expect(worstTone([])).toBeNull();
  });

  it('copes with a box where nothing has been fetched yet', () => {
    expect(buildAttention({ nowMs: NOW })).toEqual([]);
  });

  it('puts the serious things above the merely informative', () => {
    const items = buildAttention({
      ...clean(),
      updates: [update({ package: 'media', state: 'available' })],
      findings: [finding({ id: 'f1', severity: 'high', title: 'Admin password is weak' })],
      disks: {
        ...healthyDisks(),
        disks: [disk({ id: 'sdc', health: 'failing' })],
      },
    });
    expect(items.map((i) => i.tone)).toEqual(['err', 'err', 'info']);
    expect(items[items.length - 1].id).toBe('updates');
    expect(worstTone(items)).toBe('err');
  });

  it('names the failing drive rather than counting drives', () => {
    const items = buildAttention({
      ...clean(),
      disks: { ...healthyDisks(), disks: [disk({ id: 'sdc', model: 'WDC WD80EFZX', health: 'failing' })] },
    });
    expect(items[0].text).toContain('/dev/sdc');
    expect(items[0].text).toContain('WDC WD80EFZX');
    expect(items[0].to).toBe('/disks');
  });

  it('surfaces a drive reallocating sectors even though SMART still says passed', () => {
    const items = buildAttention({
      ...clean(),
      disks: { ...healthyDisks(), disks: [disk({ id: 'sdc', reallocatedSectors: 3 })] },
    });
    expect(items).toHaveLength(1);
    expect(items[0].tone).toBe('warn');
    expect(items[0].text).toContain('reallocated');
  });

  it('surfaces stale parity and a full branch separately', () => {
    const base = healthyDisks();
    const items = buildAttention({
      ...clean(),
      disks: {
        disks: base.disks,
        pool: {
          ...base.pool,
          branches: [{ diskId: 'a', path: '/mnt/disk2', totalBytes: 100 * GIB, usedBytes: 95 * GIB }],
        },
        parity: { ...base.parity, lastSyncAt: new Date(NOW - 9 * DAY).toISOString() },
      },
    });
    expect(items.map((i) => i.id).sort()).toEqual(['branch:/mnt/disk2', 'parity']);
  });

  it('raises a failed backup louder than an overdue one', () => {
    const failed = buildAttention({
      ...clean(),
      backup: { ...healthyBackup(), status: { ...healthyBackup().status, lastRunState: 'failed' } },
    });
    expect(failed[0].tone).toBe('err');

    const stale = buildAttention({
      ...clean(),
      backup: {
        ...healthyBackup(),
        status: { ...healthyBackup().status, lastRunAt: new Date(NOW - 9 * DAY).toISOString() },
      },
    });
    expect(stale[0].tone).toBe('warn');
    expect(stale[0].text).toContain('9 days');
  });

  it('quotes a single high finding rather than counting to one', () => {
    const items = buildAttention({
      ...clean(),
      findings: [finding({ id: 'f1', severity: 'high', title: 'Docker socket is exposed' })],
    });
    expect(items[0].text).toBe('Docker socket is exposed');
  });

  it('counts high findings once there is more than one, and groups the rest', () => {
    const items = buildAttention({
      ...clean(),
      findings: [
        finding({ id: 'a', severity: 'high' }),
        finding({ id: 'b', severity: 'high' }),
        finding({ id: 'c', severity: 'low' }),
      ],
    });
    expect(items.find((i) => i.id === 'security:high')?.text).toContain('2 serious');
    expect(items.find((i) => i.id === 'security:rest')?.text).toContain('1 other security finding');
  });

  it('warns about the system disk separately from the pool, because Docker dies with it', () => {
    const items = buildAttention({
      ...clean(),
      system: system({ diskUsedBytes: 92 * GIB }),
    });
    expect(items).toHaveLength(1);
    expect(items[0].id).toBe('root-disk');
    expect(items[0].tone).toBe('warn');
    expect(items[0].text).toContain('92%');
  });

  it('escalates the system disk to an error once it is nearly gone', () => {
    const items = buildAttention({ ...clean(), system: system({ diskUsedBytes: 96 * GIB }) });
    expect(items[0].tone).toBe('err');
  });

  it('stays quiet about a system disk with room left', () => {
    expect(buildAttention({ ...clean(), system: system({ diskUsedBytes: 60 * GIB }) })).toEqual([]);
  });

  it('gets the grammar right for one update and for several', () => {
    const one = buildAttention({ ...clean(), updates: [update({ package: 'a', state: 'available' })] });
    expect(one[0].text).toBe('1 app has an update waiting');

    const two = buildAttention({
      ...clean(),
      updates: [update({ package: 'a', state: 'available' }), update({ package: 'b', state: 'available' })],
    });
    expect(two[0].text).toBe('2 apps have an update waiting');
  });

  it('does not count an unknown update check as an update waiting', () => {
    expect(buildAttention({ ...clean(), updates: [update({ package: 'a', state: 'unknown' })] })).toEqual([]);
  });

  it('nudges when a newer marketplace catalogue is waiting, with a count', () => {
    const items = buildAttention({
      ...clean(),
      marketplace: { updateAvailable: true, newAppCount: 3 },
    });
    expect(items).toHaveLength(1);
    expect(items[0].id).toBe('marketplace');
    expect(items[0].tone).toBe('info');
    expect(items[0].text).toBe('The app marketplace has 3 new apps to browse');
    expect(items[0].to).toBe('/settings#marketplace');
  });

  it('singularises one new marketplace app', () => {
    const items = buildAttention({
      ...clean(),
      marketplace: { updateAvailable: true, newAppCount: 1 },
    });
    expect(items[0].text).toBe('The app marketplace has 1 new app to browse');
  });

  it('falls back to a generic marketplace nudge when there are no new apps', () => {
    const items = buildAttention({
      ...clean(),
      marketplace: { updateAvailable: true, newAppCount: 0 },
    });
    expect(items[0].text).toBe('A newer app marketplace catalogue is ready to review');
  });

  it('stays quiet when the marketplace has no pending update', () => {
    expect(buildAttention({ ...clean(), marketplace: { updateAvailable: false, newAppCount: 0 } })).toEqual([]);
  });

  it('gives every item somewhere to go', () => {
    const items = buildAttention({
      ...clean(),
      updates: [update({ package: 'a', state: 'available' })],
      findings: [finding({ id: 'f', severity: 'high' })],
      disks: { ...healthyDisks(), disks: [disk({ id: 'sdc', health: 'failing' })] },
      system: system({ diskUsedBytes: 96 * GIB }),
    });
    for (const item of items) {
      expect(item.to.startsWith('/')).toBe(true);
      expect(item.cta.length).toBeGreaterThan(0);
      expect(item.text.length).toBeGreaterThan(0);
    }
    // Ids are unique, so Vue keys stay stable across polls.
    expect(new Set(items.map((i) => i.id)).size).toBe(items.length);
  });
});
