import { describe, it, expect } from 'vitest';
import type { ContainerEventItem } from '@/api/containers';
import {
  pushDeduped,
  replaceCapped,
  pruneFailureWindow,
  MAX_EVENTS,
} from './container-events';

function e(ts: number, container: string, action: string): ContainerEventItem {
  return { ts, container, action };
}

describe('container-events helpers', () => {
  describe('pushDeduped', () => {
    it('appends a fresh event to an empty buffer', () => {
      const out = pushDeduped([], e(1, 'sonarr', 'start'));
      expect(out).toEqual([{ ts: 1, container: 'sonarr', action: 'start' }]);
    });

    it('appends a fresh event to a populated buffer', () => {
      const buf = [e(1, 'sonarr', 'start')];
      const out = pushDeduped(buf, e(2, 'sonarr', 'die'));
      expect(out.length).toBe(2);
      expect(out[1].action).toBe('die');
    });

    it('drops a same-tail duplicate and returns the input reference', () => {
      const buf = [e(1, 'sonarr', 'start'), e(2, 'sonarr', 'die')];
      const out = pushDeduped(buf, e(2, 'sonarr', 'die'));
      // Reference equality confirms no allocation on the dup path.
      expect(out).toBe(buf);
    });

    it('does not dedupe against non-tail matches', () => {
      // A collision earlier in history should still land (docker will
      // never regenerate a strictly-old (ts, container, action) triple
      // in practice, but we don't want the dedupe to silently drop it).
      const buf = [e(1, 'sonarr', 'start'), e(2, 'sonarr', 'die')];
      const out = pushDeduped(buf, e(1, 'sonarr', 'start'));
      expect(out.length).toBe(3);
      expect(out[out.length - 1].ts).toBe(1);
    });

    it('respects the cap by evicting the front on overflow', () => {
      const buf = Array.from({ length: 5 }, (_, i) => e(i, 'c', 'start'));
      const out = pushDeduped(buf, e(999, 'c', 'die'), 5);
      expect(out.length).toBe(5);
      expect(out[0].ts).toBe(1);          // 0 evicted
      expect(out[out.length - 1].ts).toBe(999);
    });

    it('MAX_EVENTS default matches the backend BUFFER_MAX contract', () => {
      // If MAX_EVENTS drifts from the backend's 200 the ring buffers
      // fall out of sync.
      expect(MAX_EVENTS).toBe(200);
    });
  });

  describe('replaceCapped', () => {
    it('returns a copy when under the cap', () => {
      const src = [e(1, 'a', 'start')];
      const out = replaceCapped(src);
      expect(out).toEqual(src);
      expect(out).not.toBe(src);
    });

    it('slices to the tail when over the cap', () => {
      const src = Array.from({ length: 250 }, (_, i) => e(i, 'c', 'start'));
      const out = replaceCapped(src);
      expect(out.length).toBe(MAX_EVENTS);
      expect(out[0].ts).toBe(50);
    });

    it('honours a custom cap', () => {
      const src = Array.from({ length: 10 }, (_, i) => e(i, 'c', 'start'));
      const out = replaceCapped(src, 3);
      expect(out.length).toBe(3);
      expect(out.map((x) => x.ts)).toEqual([7, 8, 9]);
    });
  });

  describe('pruneFailureWindow', () => {
    it('keeps timestamps inside the window', () => {
      const now = 10_000;
      const out = pruneFailureWindow([now - 1000, now - 500, now], now, 30_000);
      expect(out).toEqual([9_000, 9_500, 10_000]);
    });

    it('drops timestamps older than the window', () => {
      const now = 100_000;
      const out = pruneFailureWindow([1_000, 99_500, 100_000], now, 30_000);
      // 1_000 is 99s old (>30s) → dropped.
      expect(out).toEqual([99_500, 100_000]);
    });

    it('preserves order', () => {
      const now = 100;
      const out = pruneFailureWindow([50, 75, 100], now, 60);
      expect(out).toEqual([50, 75, 100]);
    });

    it('accepts an empty input', () => {
      expect(pruneFailureWindow([], 1, 1_000)).toEqual([]);
    });
  });
});
