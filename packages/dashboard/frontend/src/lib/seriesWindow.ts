/**
 * How long a series of samples actually covers.
 *
 * The dashboard used to label both the CPU sparkline and the metrics chart
 * "last 24 hours" unconditionally. On a box installed twenty minutes ago
 * that is a claim about eleven data points spanning a lunch break, drawn as
 * a confident straight line — the exact shape of invented state the product
 * is supposed to refuse. The window is knowable: the samples carry their own
 * timestamps.
 */
export function seriesWindowLabel(ts: readonly number[], now: number = Date.now()): string {
  if (!ts || ts.length === 0) return 'no readings yet';

  const first = ts[0];
  if (!Number.isFinite(first)) return 'no readings yet';

  const minutes = Math.max(0, Math.round((now - first) / 60_000));
  if (minutes < 2) return 'just started';
  if (minutes < 60) return `last ${minutes} minutes`;

  const hours = Math.round(minutes / 60);
  if (hours < 24) return `last ${hours} hour${hours === 1 ? '' : 's'}`;

  // A full day's worth is the ceiling: the sampler keeps 24 hours, so
  // anything older than that is not on the chart to talk about.
  return 'last 24 hours';
}
