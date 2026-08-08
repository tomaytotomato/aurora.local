import { beforeEach, describe, expect, it } from 'vitest';

import { initialUpdates } from './fixtures/updates';
import { createJob, finishJob, nextLine, publicJob } from './jobs';
import { state } from './state';

/**
 * The mock job registry is what makes `npm run dev:mock` believable, so
 * it gets the same treatment as production logic. The case that matters
 * most: a failed update must leave the package on its old version and
 * the update still waiting. A mock that quietly marks it done would hide
 * exactly the UI state the failure path exists to show.
 */

beforeEach(() => {
  state.jobs = {};
  state.updates = initialUpdates();
});

describe('createJob', () => {
  it('registers a running job with an empty log', () => {
    const job = createJob('enable', 'jellyfin');
    expect(job.state).toBe('running');
    expect(job.tail).toEqual([]);
    expect(job.finishedAt).toBeNull();
    expect(state.jobs[job.id]).toBe(job);
  });

  it('gives every job a distinct id', () => {
    const ids = [createJob('enable', 'a').id, createJob('enable', 'a').id, createJob('update', 'b').id];
    expect(new Set(ids).size).toBe(3);
  });
});

describe('nextLine', () => {
  it('walks the script once, appending to the tail as it goes', () => {
    const job = createJob('start', 'media');
    const emitted: string[] = [];
    for (;;) {
      const line = nextLine(job);
      if (line === null) break;
      emitted.push(line);
    }
    expect(emitted.length).toBeGreaterThan(0);
    expect(job.tail).toEqual(emitted);
    expect(nextLine(job)).toBeNull();
  });
});

describe('finishJob', () => {
  it('moves a job to the terminal state its script declares', () => {
    const job = createJob('start', 'media');
    finishJob(job);
    expect(job.state).toBe('success');
    expect(job.exitCode).toBe(0);
    expect(job.finishedAt).not.toBeNull();
  });

  it('is idempotent, so a duplicate done frame changes nothing', () => {
    const job = createJob('start', 'media');
    finishJob(job);
    const finishedAt = job.finishedAt;
    finishJob(job);
    expect(job.finishedAt).toBe(finishedAt);
  });

  it('clears the waiting update and moves the versions on a successful update', () => {
    expect(state.updates.jellyfin.state).toBe('available');
    const job = createJob('update', 'jellyfin');
    finishJob(job);

    const row = state.updates.jellyfin;
    expect(job.state).toBe('success');
    expect(row.state).toBe('current');
    expect(row.lastUpdateFailed).toBe(false);
    expect(row.lastUpdateJobId).toBe(job.id);
    expect(row.images[0].currentTag).toBe('10.10.0');
    expect(row.images.every((i) => i.state === 'current')).toBe(true);
  });

  it('leaves the package on its old version when the update fails', () => {
    // `ai` is the fixture's rate-limited package.
    const before = state.updates.ai.images[0].currentTag;
    const job = createJob('update', 'ai');
    finishJob(job);

    const row = state.updates.ai;
    expect(job.state).toBe('failed');
    expect(job.failureCode).toBe('pull_rate_limited');
    expect(row.state).toBe('available');
    expect(row.lastUpdateFailed).toBe(true);
    expect(row.lastUpdateJobId).toBe(job.id);
    expect(row.images[0].currentTag).toBe(before);
  });

  it('does not fall over on an update for a package with no update row', () => {
    const job = createJob('update', 'not-a-package');
    expect(() => finishJob(job)).not.toThrow();
    expect(job.state).toBe('success');
  });
});

describe('publicJob', () => {
  it('strips the mock-only bookkeeping before it goes over the wire', () => {
    const job = createJob('deploy', 'my-stack');
    const wire = publicJob(job) as unknown as Record<string, unknown>;
    expect(wire.script).toBeUndefined();
    expect(wire.cursor).toBeUndefined();
    expect(wire.id).toBe(job.id);
    expect(wire.kind).toBe('deploy');
  });
});
