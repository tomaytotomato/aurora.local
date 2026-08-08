import { describe, expect, it } from 'vitest';

import {
  backupHeadline,
  backupPageState,
  backupTone,
  daysSinceLastRun,
  dedupSavingPct,
  sourcesAtRisk,
  type BackupSource,
  type BackupStatus,
} from './backup';

const NOW = Date.parse('2026-08-08T12:00:00Z');
const DAY = 86_400_000;

function status(over: Partial<BackupStatus> = {}): BackupStatus {
  return {
    repoState: 'connected',
    repoKind: 'filesystem',
    repoLocation: '/repository',
    encrypted: true,
    totalSizeBytes: 400,
    uniqueSizeBytes: 100,
    snapshotCount: 12,
    lastRunAt: new Date(NOW - 2 * 3_600_000).toISOString(),
    lastRunState: 'ok',
    lastRunDurationMs: 60_000,
    nextRunAt: null,
    generatedAt: new Date(NOW).toISOString(),
    ...over,
  };
}

function source(over: Partial<BackupSource> & { id: string }): BackupSource {
  return {
    path: '/data/thing',
    package: null,
    enabled: true,
    lastSnapshotAt: null,
    lastSnapshotState: 'ok',
    sizeBytes: 1,
    fileCount: 1,
    beforeActions: [],
    needsConsistencyAction: false,
    ...over,
  };
}

describe('daysSinceLastRun', () => {
  it('counts whole days since the last successful run', () => {
    expect(daysSinceLastRun(status({ lastRunAt: new Date(NOW - 3 * DAY).toISOString() }), NOW)).toBe(3);
  });

  it('reads a run earlier today as zero days, not as never', () => {
    expect(daysSinceLastRun(status(), NOW)).toBe(0);
  });

  it('ignores a failed run, because a failure is not a backup', () => {
    const s = status({ lastRunState: 'failed', lastRunAt: new Date(NOW - 1 * DAY).toISOString() });
    expect(daysSinceLastRun(s, NOW)).toBeNull();
  });

  it('returns null when nothing has ever run', () => {
    expect(daysSinceLastRun(status({ lastRunAt: null, lastRunState: null }), NOW)).toBeNull();
  });
});

describe('backupPageState', () => {
  const policy = { stalenessWarnDays: 3 };

  it('is healthy when the last run succeeded inside the window', () => {
    expect(backupPageState(status(), policy, NOW)).toBe('healthy');
  });

  it('goes stale once the window has passed', () => {
    const s = status({ lastRunAt: new Date(NOW - 4 * DAY).toISOString() });
    expect(backupPageState(s, policy, NOW)).toBe('stale');
  });

  it('treats the threshold itself as stale, so "warn after 3 days" means 3', () => {
    const s = status({ lastRunAt: new Date(NOW - 3 * DAY).toISOString() });
    expect(backupPageState(s, policy, NOW)).toBe('stale');
  });

  it('keeps a failed run distinct from a stale schedule', () => {
    // Both are wrong, but one failed loudly last night and the other
    // stopped quietly weeks ago. They need different copy.
    const failedLastNight = status({ lastRunState: 'failed' });
    expect(backupPageState(failedLastNight, policy, NOW)).toBe('failed');
  });

  it('reads a box that has never run as stale rather than healthy', () => {
    const s = status({ lastRunAt: null, lastRunState: null });
    expect(backupPageState(s, policy, NOW)).toBe('stale');
  });

  it('reports an unreachable repository before anything about runs', () => {
    const s = status({ repoState: 'unreachable', lastRunState: 'failed' });
    expect(backupPageState(s, policy, NOW)).toBe('unreachable');
  });

  it('reports a missing repository first of all', () => {
    expect(backupPageState(status({ repoState: 'not-configured' }), policy, NOW)).toBe('not-configured');
  });
});

describe('backupTone', () => {
  it('only calls a backup healthy when it is', () => {
    expect(backupTone('healthy')).toBe('ok');
    expect(backupTone('stale')).toBe('warn');
    expect(backupTone('failed')).toBe('err');
    expect(backupTone('unreachable')).toBe('err');
    expect(backupTone('not-configured')).toBe('neutral');
  });
});

describe('backupHeadline', () => {
  it('says how long it has been, in days, when things are fine', () => {
    expect(backupHeadline('healthy', 0)).toBe('Backed up today');
    expect(backupHeadline('healthy', 1)).toBe('Backed up 1 day ago');
    expect(backupHeadline('healthy', 5)).toBe('Backed up 5 days ago');
  });

  it('distinguishes "never backed up" from "overdue"', () => {
    expect(backupHeadline('stale', null)).toBe('Nothing has been backed up yet');
    expect(backupHeadline('stale', 9)).toBe('No successful backup in 9 days');
  });

  it('never returns an empty string for any state', () => {
    for (const state of ['not-configured', 'unreachable', 'failed', 'stale', 'healthy'] as const) {
      expect(backupHeadline(state, 2).length).toBeGreaterThan(0);
    }
  });
});

describe('sourcesAtRisk', () => {
  it('flags a source holding a database with nothing dumping it first', () => {
    const rows = [
      source({ id: 'ok' }),
      source({ id: 'documents', needsConsistencyAction: true }),
    ];
    expect(sourcesAtRisk(rows).map((s) => s.id)).toEqual(['documents']);
  });

  it('flags a source whose last snapshot failed', () => {
    const rows = [source({ id: 'git', lastSnapshotState: 'failed' })];
    expect(sourcesAtRisk(rows)).toHaveLength(1);
  });

  it('ignores a source that is switched off, since it is not claiming to be protected', () => {
    const rows = [source({ id: 'media', enabled: false, needsConsistencyAction: true })];
    expect(sourcesAtRisk(rows)).toEqual([]);
  });
});

describe('dedupSavingPct', () => {
  it('reports what deduplication actually saved', () => {
    expect(dedupSavingPct({ totalSizeBytes: 400, uniqueSizeBytes: 100 })).toBe(75);
  });

  it('is zero when nothing was saved', () => {
    expect(dedupSavingPct({ totalSizeBytes: 100, uniqueSizeBytes: 100 })).toBe(0);
  });

  it('returns null rather than a fabricated figure when either side is missing', () => {
    expect(dedupSavingPct({ totalSizeBytes: null, uniqueSizeBytes: 100 })).toBeNull();
    expect(dedupSavingPct({ totalSizeBytes: 400, uniqueSizeBytes: null })).toBeNull();
    expect(dedupSavingPct({ totalSizeBytes: 0, uniqueSizeBytes: 0 })).toBeNull();
  });
});
