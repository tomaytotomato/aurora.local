import { describe, it, expect } from 'vitest';
import { seriesWindowLabel } from './seriesWindow';

const NOW = Date.parse('2026-08-28T20:00:00Z');
const minutesAgo = (m: number) => NOW - m * 60_000;

describe('seriesWindowLabel', () => {
  it('says nothing rather than claiming a day of history it does not have', () => {
    expect(seriesWindowLabel([], NOW)).toBe('no readings yet');
  });

  it('describes a freshly installed box honestly', () => {
    // The case that made this exist: a chart labelled "last 24 hours" on a
    // box that had existed for twenty minutes.
    expect(seriesWindowLabel([minutesAgo(20), minutesAgo(15)], NOW)).toBe('last 20 minutes');
    expect(seriesWindowLabel([minutesAgo(1)], NOW)).toBe('just started');
  });

  it('rounds up to hours once there are some', () => {
    expect(seriesWindowLabel([minutesAgo(65)], NOW)).toBe('last 1 hour');
    expect(seriesWindowLabel([minutesAgo(200)], NOW)).toBe('last 3 hours');
  });

  it('caps at the sampler\u2019s own retention', () => {
    expect(seriesWindowLabel([minutesAgo(60 * 40)], NOW)).toBe('last 24 hours');
  });
});
