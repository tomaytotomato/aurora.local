import { describe, expect, it } from 'vitest';

import type { JobFailureCode, JobKind } from '@/api/jobs';

import { jobFailureCopy, jobHeadline } from './job-copy';

const ALL_KINDS: JobKind[] = [
  'enable',
  'disable',
  'update',
  'update-check',
  'start',
  'restart',
  'backup',
  'restore',
  'deploy',
];

describe('jobHeadline', () => {
  it('phrases the running state as an action in progress for every kind', () => {
    for (const kind of ALL_KINDS) {
      expect(jobHeadline(kind, 'running')).toMatch(/…$/);
    }
  });

  it('treats queued the same as running, so a job never reads as finished before it starts', () => {
    for (const kind of ALL_KINDS) {
      expect(jobHeadline(kind, 'queued')).toBe(jobHeadline(kind, 'running'));
    }
  });

  it('gives every kind a distinct headline per terminal state', () => {
    for (const kind of ALL_KINDS) {
      const success = jobHeadline(kind, 'success');
      const failed = jobHeadline(kind, 'failed');
      expect(success).not.toBe(failed);
      expect(success).not.toMatch(/…$/);
      expect(failed).not.toMatch(/…$/);
    }
  });

  it('names the action rather than saying "job"', () => {
    expect(jobHeadline('update', 'running')).toBe('Updating…');
    expect(jobHeadline('enable', 'success')).toBe('App added');
    expect(jobHeadline('restore', 'failed')).toBe('Restore failed');
  });
});

describe('jobFailureCopy', () => {
  const CODED: Exclude<JobFailureCode, 'unknown'>[] = [
    'port_conflict',
    'pull_rate_limited',
    'registry_unreachable',
    'disk_full',
    'docker_down',
    'container_crashed',
  ];

  it('prefers its own copy over the raw reason for every known code', () => {
    for (const code of CODED) {
      const copy = jobFailureCopy(code, 'exit status 125: toomanyrequests');
      expect(copy).not.toContain('exit status');
      expect(copy.length).toBeGreaterThan(20);
    }
  });

  it('says nothing was changed when a pull was rate limited, because nothing was', () => {
    expect(jobFailureCopy('pull_rate_limited', null)).toContain('Nothing on the box was changed');
  });

  it('falls back to the job reason when the code is unknown', () => {
    expect(jobFailureCopy('unknown', 'The compose file references a missing volume.')).toBe(
      'The compose file references a missing volume.',
    );
  });

  it('falls back to the job reason when there is no code at all', () => {
    expect(jobFailureCopy(null, 'Something specific went wrong.')).toBe('Something specific went wrong.');
  });

  it('never returns an empty string, since the caller renders it inside an alert', () => {
    expect(jobFailureCopy(null, null)).not.toBe('');
    expect(jobFailureCopy(null, '   ')).not.toBe('');
    expect(jobFailureCopy('unknown', '')).not.toBe('');
  });
});
