// B2-followup (iter-22): metrics read surface.
//
// Endpoints:
//   GET /api/metrics/keys?prefix=<optional>  → List<String>
//   GET /api/metrics/last24h?key=…&bucketMinutes=5
//     → [{ ts: unix-ms, avg, min, max, count }, ...]
//
// Auth: session cookie required (SecurityConfig .anyRequest().authenticated()).

import { http } from './client';

/** Wall-clock-aligned bucket returned by the backend. */
export interface MetricBucket {
  /** Bucket start, unix milliseconds. */
  ts: number;
  avg: number;
  min: number;
  max: number;
  count: number;
}

/** Bucket widths the backend accepts. */
export type BucketMinutes = 1 | 2 | 5 | 10 | 15 | 30 | 60;

export const MetricsApi = {
  /** List distinct metric keys, optionally filtered by prefix. */
  async keys(prefix?: string): Promise<string[]> {
    const { data } = await http.get<string[]>('/metrics/keys', {
      params: prefix ? { prefix } : {},
    });
    return data;
  },

  /** Fetch last-24h bucketed series for a single metric key. */
  async last24h(key: string, bucketMinutes: BucketMinutes = 5): Promise<MetricBucket[]> {
    const { data } = await http.get<MetricBucket[]>('/metrics/last24h', {
      params: { key, bucketMinutes },
    });
    return data;
  },
};
