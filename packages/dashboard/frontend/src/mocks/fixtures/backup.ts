// Backup fixtures. Dates are relative to now so the page keeps reading
// "backed up today" however long this sits in the repo.
//
// EDIT ME to reach a state you want to look at:
//   • initialStatus().repoState = 'not-configured'  → the first-run view
//   • initialStatus().repoState = 'unreachable'     → the honest error view
//   • initialStatus().lastRunState = 'failed'       → the failure banner
//   • LAST_RUN_DAYS_AGO = 9                         → the stale warning
//
// The `documents` source is deliberately left with a database and no
// before-snapshot action, because that warning row is the main reason
// the What's protected tab exists.

import type { BackupPolicy, BackupSource, BackupStatus, Snapshot } from '@/api/backup';

const MIB = 1024 ** 2;
const GIB = 1024 ** 3;

/** Bump this to walk the page into its stale state. */
const LAST_RUN_DAYS_AGO = 0;

function hoursAgo(h: number): string {
  return new Date(Date.now() - h * 3_600_000).toISOString();
}
function daysAgo(d: number): string {
  return new Date(Date.now() - d * 86_400_000).toISOString();
}
function hoursAhead(h: number): string {
  return new Date(Date.now() + h * 3_600_000).toISOString();
}

export function initialStatus(): BackupStatus {
  return {
    repoState: 'connected',
    repoKind: 'filesystem',
    repoLocation: '/repository (data/backup/repository)',
    encrypted: true,
    totalSizeBytes: Math.round(412 * GIB),
    uniqueSizeBytes: Math.round(168 * GIB),
    snapshotCount: 87,
    lastRunAt: LAST_RUN_DAYS_AGO === 0 ? hoursAgo(9) : daysAgo(LAST_RUN_DAYS_AGO),
    lastRunState: 'ok',
    lastRunDurationMs: 14 * 60 * 1000 + 22_000,
    nextRunAt: hoursAhead(15),
    generatedAt: new Date().toISOString(),
  };
}

export function initialPolicy(): BackupPolicy {
  return {
    scheduleCron: '0 2 * * *',
    scheduleLabel: 'Every day at 02:00',
    keepDaily: 7,
    keepWeekly: 4,
    keepMonthly: 6,
    stalenessWarnDays: 3,
  };
}

export function initialSources(): BackupSource[] {
  return [
    {
      id: 'src-aurora',
      path: '/data/home/bruce/aurora.local',
      package: 'core',
      enabled: true,
      lastSnapshotAt: hoursAgo(9),
      lastSnapshotState: 'ok',
      sizeBytes: Math.round(48 * MIB),
      fileCount: 1_204,
      beforeActions: [],
      needsConsistencyAction: false,
    },
    {
      id: 'src-photos',
      path: '/data/photos/library',
      package: 'photos',
      enabled: true,
      lastSnapshotAt: hoursAgo(9),
      lastSnapshotState: 'ok',
      sizeBytes: Math.round(308 * GIB),
      fileCount: 94_612,
      beforeActions: [
        {
          kind: 'postgres-dump',
          container: 'immich-postgres',
          description: 'Dumps the Immich database so the snapshot restores cleanly',
        },
      ],
      needsConsistencyAction: false,
    },
    {
      id: 'src-documents',
      path: '/data/documents',
      package: 'documents',
      enabled: true,
      lastSnapshotAt: hoursAgo(9),
      lastSnapshotState: 'ok',
      sizeBytes: Math.round(22 * GIB),
      fileCount: 8_431,
      // Paperless keeps a Postgres database under this path and nothing
      // dumps it first, so the snapshot is a copy of a live data
      // directory. This is the warning row.
      beforeActions: [],
      needsConsistencyAction: true,
    },
    {
      id: 'src-notes',
      path: '/data/notes',
      package: 'notes',
      enabled: true,
      lastSnapshotAt: hoursAgo(9),
      lastSnapshotState: 'ok',
      sizeBytes: Math.round(96 * MIB),
      fileCount: 612,
      beforeActions: [
        {
          kind: 'sqlite-backup',
          container: 'silverbullet',
          description: 'Checkpoints the SilverBullet database before copying it',
        },
      ],
      needsConsistencyAction: false,
    },
    {
      id: 'src-filebrowser',
      path: '/data/filebrowser',
      package: 'filebrowser',
      enabled: true,
      // One source that failed last night while the rest succeeded: the
      // page must not call this "protected".
      lastSnapshotAt: daysAgo(2),
      lastSnapshotState: 'failed',
      sizeBytes: Math.round(3.4 * GIB),
      fileCount: 21_004,
      beforeActions: [],
      needsConsistencyAction: false,
    },
    {
      id: 'src-media',
      path: '/data/media',
      package: 'media',
      // Deliberately off: 4 TB of films that can be downloaded again is
      // not worth parity space, and the UI should show that as a
      // decision rather than an omission.
      enabled: false,
      lastSnapshotAt: null,
      lastSnapshotState: null,
      sizeBytes: null,
      fileCount: null,
      beforeActions: [],
      needsConsistencyAction: false,
    },
  ];
}

export function initialSnapshots(): Snapshot[] {
  const rows: Snapshot[] = [];
  const sources: Array<[string, string, number, number, boolean]> = [
    ['src-aurora', '/data/home/bruce/aurora.local', 48 * MIB, 1_204, true],
    ['src-photos', '/data/photos/library', 308 * GIB, 94_612, true],
    ['src-documents', '/data/documents', 22 * GIB, 8_431, false],
    ['src-notes', '/data/notes', 96 * MIB, 612, true],
  ];

  for (const [sourceId, path, size, files, consistent] of sources) {
    for (let day = 0; day < 7; day += 1) {
      rows.push({
        id: `snap-${sourceId}-${day}`,
        sourceId,
        path,
        createdAt: day === 0 ? hoursAgo(9) : daysAgo(day),
        state: 'ok',
        // Later snapshots are slightly larger; dedup means the delta is small.
        sizeBytes: Math.round(size * (1 - day * 0.004)),
        fileCount: files - day * 12,
        consistent,
      });
    }
  }

  // The git source's most recent attempt failed, and the one before it
  // only got part of the way.
  rows.push({
    id: 'snap-src-git-0',
    sourceId: 'src-git',
    path: '/data/git',
    createdAt: daysAgo(2),
    state: 'failed',
    sizeBytes: 0,
    fileCount: 0,
    consistent: false,
  });
  rows.push({
    id: 'snap-src-git-1',
    sourceId: 'src-git',
    path: '/data/git',
    createdAt: daysAgo(3),
    state: 'partial',
    sizeBytes: Math.round(2.1 * GIB),
    fileCount: 14_882,
    consistent: false,
  });

  return rows.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}
