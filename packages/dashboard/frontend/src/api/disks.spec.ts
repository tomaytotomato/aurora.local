import { describe, expect, it } from 'vitest';

import {
  branchUsedPct,
  daysSinceSync,
  diskAttention,
  diskTone,
  disksNeedingAttention,
  disksPageState,
  fullBranches,
  parityFreshness,
  parityHeadline,
  parityTone,
  poolUsedPct,
  sortByHealth,
  type Disk,
  type Parity,
  type Pool,
} from './disks';

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

function pool(over: Partial<Pool> = {}): Pool {
  return {
    configured: true,
    mountpoint: '/mnt/storage',
    totalBytes: 200 * GIB,
    usedBytes: 100 * GIB,
    branches: [
      { diskId: 'a', path: '/mnt/disk1', totalBytes: 100 * GIB, usedBytes: 50 * GIB },
      { diskId: 'b', path: '/mnt/disk2', totalBytes: 100 * GIB, usedBytes: 50 * GIB },
    ],
    createPolicy: 'mfs',
    minFreeBytes: 20 * GIB,
    ...over,
  };
}

function parity(over: Partial<Parity> = {}): Parity {
  return {
    configured: true,
    parityDiskIds: ['p'],
    lastSyncAt: new Date(NOW - DAY).toISOString(),
    lastSyncState: 'ok',
    lastScrubAt: null,
    pendingChanges: 10,
    deletedSinceSync: 2,
    deletionThreshold: 200,
    stalenessWarnDays: 3,
    ...over,
  };
}

describe('diskAttention', () => {
  it('flags a reallocating drive even while SMART overall still says passed', () => {
    // The case this whole page exists for: SMART's verdict is set by the
    // manufacturer and errs generous.
    const d = disk({ id: 'sdc', health: 'passed', reallocatedSectors: 3 });
    expect(diskAttention(d)).toBe('3 reallocated sectors');
    expect(diskTone(d)).toBe('warn');
  });

  it('uses the singular for exactly one sector', () => {
    expect(diskAttention(disk({ id: 'x', reallocatedSectors: 1 }))).toBe('1 reallocated sector');
  });

  it('flags pending sectors too', () => {
    expect(diskAttention(disk({ id: 'x', pendingSectors: 2 }))).toContain('pending reallocation');
  });

  it('puts an outright failing drive above everything else', () => {
    const d = disk({ id: 'x', health: 'failing', reallocatedSectors: 40 });
    expect(diskAttention(d)).toBe('SMART reports this drive as failing');
    expect(diskTone(d)).toBe('err');
  });

  it('flags a self-test that did not complete cleanly', () => {
    const d = disk({ id: 'x', lastSelfTestResult: 'Completed: read failure' });
    expect(diskAttention(d)).toContain('self-test');
    expect(diskAttention(disk({ id: 'y', lastSelfTestResult: 'Aborted by host' }))).toContain('self-test');
  });

  it('does not read the healthy result as a failure just because it contains the word "error"', () => {
    // "Completed without error" is what a good drive says, and it matches
    // any naive search for the word. This was a real bug.
    expect(diskAttention(disk({ id: 'x', lastSelfTestResult: 'Completed without error' }))).toBeNull();
  });

  it('does not treat a test interrupted by a reboot as a fault', () => {
    expect(diskAttention(disk({ id: 'x', lastSelfTestResult: 'Interrupted (host reset)' }))).toBeNull();
  });

  it('leaves a healthy drive alone', () => {
    expect(diskAttention(disk({ id: 'x' }))).toBeNull();
    expect(diskTone(disk({ id: 'x' }))).toBe('ok');
  });

  it('does not moan about ordinary running temperatures', () => {
    expect(diskAttention(disk({ id: 'x', temperatureC: 45 }))).toBeNull();
    expect(diskAttention(disk({ id: 'x', temperatureC: 56 }))).toContain('56');
  });

  it('reads a drive with no SMART support as neutral, never as passing', () => {
    const d = disk({
      id: 'usb',
      health: 'unknown',
      reallocatedSectors: null,
      pendingSectors: null,
      temperatureC: null,
      lastSelfTestResult: null,
    });
    expect(diskTone(d)).toBe('neutral');
    expect(diskAttention(d)).toBeNull();
  });
});

describe('disksNeedingAttention / sortByHealth', () => {
  const rows = [
    disk({ id: 'fine' }),
    disk({ id: 'warn', reallocatedSectors: 2 }),
    disk({ id: 'dying', health: 'failing' }),
  ];

  it('returns only the drives worth looking at, worst first', () => {
    expect(disksNeedingAttention(rows).map((d) => d.id)).toEqual(['dying', 'warn']);
  });

  it('sorts the full table worst first so the row you came for is at the top', () => {
    expect(sortByHealth(rows).map((d) => d.id)).toEqual(['dying', 'warn', 'fine']);
  });

  it('does not mutate the array it was given', () => {
    const original = [...rows];
    sortByHealth(rows);
    expect(rows).toEqual(original);
  });
});

describe('poolUsedPct / branchUsedPct / fullBranches', () => {
  it('reports pool usage as a percentage', () => {
    expect(poolUsedPct(pool())).toBe(50);
  });

  it('returns null rather than a fabricated figure when the pool size is unknown', () => {
    expect(poolUsedPct(pool({ totalBytes: null }))).toBeNull();
    expect(poolUsedPct(pool({ totalBytes: 0 }))).toBeNull();
  });

  it('finds the branch mergerfs has quietly stopped writing to', () => {
    // 12 GB free against a 20 GB floor: the pool still reads 56% overall,
    // which is exactly how this gets missed.
    const p = pool({
      branches: [
        { diskId: 'a', path: '/mnt/disk1', totalBytes: 100 * GIB, usedBytes: 12 * GIB },
        { diskId: 'b', path: '/mnt/disk2', totalBytes: 100 * GIB, usedBytes: 88 * GIB },
      ],
    });
    expect(fullBranches(p).map((b) => b.path)).toEqual(['/mnt/disk2']);
  });

  it('finds nothing when mergerfs has no floor configured', () => {
    expect(fullBranches(pool({ minFreeBytes: null }))).toEqual([]);
  });

  it('reports per-branch usage', () => {
    expect(branchUsedPct({ diskId: 'a', path: '/x', totalBytes: 100, usedBytes: 25 })).toBe(25);
    expect(branchUsedPct({ diskId: 'a', path: '/x', totalBytes: 0, usedBytes: 0 })).toBe(0);
  });
});

describe('parityFreshness', () => {
  it('is fresh inside the window', () => {
    expect(parityFreshness(parity(), NOW)).toBe('fresh');
  });

  it('goes stale on the threshold', () => {
    expect(parityFreshness(parity({ lastSyncAt: new Date(NOW - 3 * DAY).toISOString() }), NOW)).toBe('stale');
  });

  it('keeps an aborted sync distinct from a failed one', () => {
    // The runner refusing to sync past the deletion threshold is a guard
    // working, not a fault — but parity is still not current.
    expect(parityFreshness(parity({ lastSyncState: 'aborted' }), NOW)).toBe('aborted');
    expect(parityTone('aborted')).toBe('warn');
    expect(parityFreshness(parity({ lastSyncState: 'failed' }), NOW)).toBe('failed');
    expect(parityTone('failed')).toBe('err');
  });

  it('reports a box with no parity disk as such rather than as stale', () => {
    expect(parityFreshness(parity({ configured: false }), NOW)).toBe('not-configured');
  });

  it('reports never-synced parity as never', () => {
    expect(parityFreshness(parity({ lastSyncAt: null, lastSyncState: 'never' }), NOW)).toBe('never');
  });
});

describe('daysSinceSync', () => {
  it('counts whole days', () => {
    expect(daysSinceSync(parity({ lastSyncAt: new Date(NOW - 5 * DAY).toISOString() }), NOW)).toBe(5);
  });

  it('is null when parity has never run', () => {
    expect(daysSinceSync(parity({ lastSyncAt: null, lastSyncState: 'never' }), NOW)).toBeNull();
  });
});

describe('parityHeadline', () => {
  it('spells out the deletion guard, including both numbers', () => {
    const line = parityHeadline(parity({ lastSyncState: 'aborted', deletedSinceSync: 412 }), NOW);
    expect(line).toContain('412');
    expect(line).toContain('200');
  });

  it('says how old parity is when it is stale', () => {
    expect(parityHeadline(parity({ lastSyncAt: new Date(NOW - 6 * DAY).toISOString() }), NOW)).toBe(
      'Parity is 6 days old',
    );
  });

  it('never returns an empty string', () => {
    for (const p of [parity(), parity({ configured: false }), parity({ lastSyncState: 'failed' })]) {
      expect(parityHeadline(p, NOW).length).toBeGreaterThan(0);
    }
  });
});

describe('disksPageState', () => {
  it('is healthy when every part is', () => {
    expect(disksPageState([disk({ id: 'a' })], pool(), parity(), NOW)).toBe('healthy');
  });

  it('reports failing above everything else', () => {
    expect(disksPageState([disk({ id: 'a', health: 'failing' })], pool(), parity(), NOW)).toBe('failing');
  });

  it('treats a failed parity sync as failing, since it means no rebuild', () => {
    expect(disksPageState([disk({ id: 'a' })], pool(), parity({ lastSyncState: 'failed' }), NOW)).toBe('failing');
  });

  it('asks for attention when a drive is reallocating', () => {
    expect(disksPageState([disk({ id: 'a', reallocatedSectors: 1 })], pool(), parity(), NOW)).toBe('attention');
  });

  it('asks for attention when a branch has filled past the mergerfs floor', () => {
    const p = pool({
      branches: [{ diskId: 'a', path: '/mnt/disk1', totalBytes: 100 * GIB, usedBytes: 95 * GIB }],
    });
    expect(disksPageState([disk({ id: 'a' })], p, parity(), NOW)).toBe('attention');
  });

  it('treats a single-disk box as no-pool rather than as broken', () => {
    const single = pool({ configured: false, branches: [] });
    expect(disksPageState([disk({ id: 'a' })], single, parity({ configured: false }), NOW)).toBe('no-pool');
  });
});
