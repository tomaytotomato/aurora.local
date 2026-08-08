import { describe, expect, it } from 'vitest';

import { formatElapsed, isTerminal, jobElapsedMs, jobTone } from './jobs';

describe('isTerminal', () => {
  it('treats success and failed as finished', () => {
    expect(isTerminal('success')).toBe(true);
    expect(isTerminal('failed')).toBe(true);
  });

  it('treats queued and running as still going', () => {
    expect(isTerminal('queued')).toBe(false);
    expect(isTerminal('running')).toBe(false);
  });
});

describe('jobTone', () => {
  it('maps terminal states to the badge vocabulary', () => {
    expect(jobTone('success')).toBe('ok');
    expect(jobTone('failed')).toBe('err');
  });

  it('leaves an in-flight job neutral rather than optimistic', () => {
    expect(jobTone('running')).toBe('neutral');
    expect(jobTone('queued')).toBe('neutral');
  });
});

describe('jobElapsedMs', () => {
  const started = '2026-08-08T12:00:00Z';

  it('measures against now while the job is still running', () => {
    const now = Date.parse('2026-08-08T12:00:45Z');
    expect(jobElapsedMs({ startedAt: started, finishedAt: null }, now)).toBe(45_000);
  });

  it('measures against the finish time once the job has ended, so it stops ticking', () => {
    const now = Date.parse('2026-08-08T13:00:00Z');
    const ms = jobElapsedMs({ startedAt: started, finishedAt: '2026-08-08T12:00:30Z' }, now);
    expect(ms).toBe(30_000);
  });

  it('never goes negative when clocks disagree', () => {
    const now = Date.parse('2026-08-08T11:59:00Z');
    expect(jobElapsedMs({ startedAt: started, finishedAt: null }, now)).toBe(0);
  });

  it('returns null for an unparseable start, so the caller renders nothing instead of 0s', () => {
    expect(jobElapsedMs({ startedAt: 'not a date', finishedAt: null }, Date.now())).toBeNull();
  });
});

describe('formatElapsed', () => {
  it('uses seconds under a minute', () => {
    expect(formatElapsed(8_000)).toBe('8s');
    expect(formatElapsed(0)).toBe('0s');
  });

  it('pads the seconds once minutes appear, so the width stops jumping', () => {
    expect(formatElapsed(124_000)).toBe('2m 04s');
    expect(formatElapsed(600_000)).toBe('10m 00s');
  });

  it('renders nothing when there is no measurement', () => {
    expect(formatElapsed(null)).toBe('');
  });
});
