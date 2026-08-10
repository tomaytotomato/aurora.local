// Job registry for the mock layer. Actions that produce a log (add,
// remove, update, start, restore, deploy) call `createJob`, hand the id
// back to the caller, and the /jobs handlers play the script out.
//
// A job keeps its own cursor, so a JobLogPanel that reconnects mid-run
// replays the lines it missed and then carries on live. A job that has
// already finished replays its whole tail and terminates immediately,
// which is what makes "open the log of a job that failed an hour ago"
// work in dev.

import type { JobKind, JobStatus } from '@/api/jobs';

import { jobScript } from './fixtures/jobs';
import { state, type MockJob } from './state';

let seq = 0;

export function createJob(kind: JobKind, target: string | null): MockJob {
  seq += 1;
  const job: MockJob = {
    id: `job-${kind}-${seq}`,
    kind,
    target,
    state: 'running',
    startedAt: new Date().toISOString(),
    finishedAt: null,
    exitCode: null,
    failureCode: null,
    failureReason: null,
    tail: [],
    script: jobScript(kind, target),
    cursor: 0,
  };
  state.jobs[job.id] = job;
  return job;
}

/**
 * Emit the next scripted line, or null when the script is spent. Appends
 * to the job's own tail so a later GET /jobs/{id} sees the same history.
 */
export function nextLine(job: MockJob): string | null {
  if (job.cursor >= job.script.lines.length) return null;
  const line = job.script.lines[job.cursor];
  job.cursor += 1;
  job.tail.push(line);
  return line;
}

/** Move a job to its scripted terminal state. Idempotent. */
export function finishJob(job: MockJob): void {
  if (job.state === 'success' || job.state === 'failed') return;
  job.state = job.script.outcome;
  job.finishedAt = new Date().toISOString();
  job.exitCode = job.script.exitCode;
  job.failureCode = job.script.failureCode;
  job.failureReason = job.script.failureReason;

  // A backup that succeeds is the thing the whole page reports on, so the
  // status has to move with it or the mock contradicts itself.
  if (job.kind === 'backup') {
    state.backup.status = {
      ...state.backup.status,
      lastRunAt: job.finishedAt,
      lastRunState: job.state === 'success' ? 'ok' : 'failed',
      snapshotCount:
        job.state === 'success' ? state.backup.status.snapshotCount + 1 : state.backup.status.snapshotCount,
      generatedAt: job.finishedAt ?? state.backup.status.generatedAt,
    };
  }

  // A parity sync is the thing the Disks page reports freshness from, so
  // a successful one has to move it or the page contradicts the log the
  // operator just watched.
  if (job.kind === 'parity-sync' && job.state === 'success') {
    state.disks.parity = {
      ...state.disks.parity,
      lastSyncAt: job.finishedAt,
      lastSyncState: 'ok',
      pendingChanges: 0,
      deletedSinceSync: 0,
    };
  }
  if (job.kind === 'parity-scrub' && job.state === 'success') {
    state.disks.parity = { ...state.disks.parity, lastScrubAt: job.finishedAt };
  }

  // An update that fails leaves the package on its old version and the
  // update still waiting; an update that succeeds clears it. The updates
  // fixture is the thing the cards read, so keep it honest.
  if (job.kind === 'update' && job.target) {
    const u = state.updates[job.target];
    if (u) {
      u.lastUpdateJobId = job.id;
      u.lastUpdateFailed = job.state === 'failed';
      if (job.state === 'success') {
        u.state = 'current';
        u.lastUpdatedAt = job.finishedAt;
        u.images = u.images.map((i) => ({
          ...i,
          currentTag: i.latestTag ?? i.currentTag,
          currentDigest: i.latestDigest ?? i.currentDigest,
          state: 'current' as const,
        }));
      }
    }
  }
}

/** Strip the mock-only bookkeeping before it goes over the wire. */
export function publicJob(job: MockJob): JobStatus {
  const { script: _script, cursor: _cursor, ...rest } = job;
  return rest;
}
