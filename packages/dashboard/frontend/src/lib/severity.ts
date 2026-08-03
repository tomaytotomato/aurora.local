// iter-35 (v0.3 followup): pure-function severity helpers shared by the
// Sidebar badge + DashboardHome pill + document.title prefix. Extracted
// into a tiny module so vitest can pin the arithmetic without mounting
// a full Vue component.

import type { SecurityFinding, SecuritySeverity } from '@/api/security';

/**
 * Counts of open findings by severity bucket. Keeps 'other' for future
 * severities added by rules the FE doesn't recognise yet (e.g. a
 * 'critical' tier).
 */
export interface SeverityCounts {
  high: number;
  medium: number;
  low: number;
  other: number;
}

export const EMPTY_COUNTS: SeverityCounts = { high: 0, medium: 0, low: 0, other: 0 };

/** Sum a findings array into per-severity buckets. Unknown severities → 'other'. */
export function countBySeverity(findings: readonly SecurityFinding[]): SeverityCounts {
  const c: SeverityCounts = { high: 0, medium: 0, low: 0, other: 0 };
  for (const f of findings) {
    switch (f.severity) {
      case 'high':   c.high++; break;
      case 'medium': c.medium++; break;
      case 'low':    c.low++; break;
      default:       c.other++;
    }
  }
  return c;
}

/** Total across all buckets. */
export function totalCount(c: SeverityCounts): number {
  return c.high + c.medium + c.low + c.other;
}

/**
 * Badge tone key for the highest severity present. Precedence:
 * high → err, medium → warn, low → info. 'other' folds into
 * 'info' so an unknown severity still shows a neutral-looking pill
 * rather than nothing at all.
 */
export type SeverityTone = 'err' | 'warn' | 'info';

export function highestSeverityTone(c: SeverityCounts): SeverityTone {
  if (c.high > 0) return 'err';
  if (c.medium > 0) return 'warn';
  return 'info';
}

/**
 * document.title severity glyph. Matches highestSeverityTone precedence
 * with symbols that stay meaningful in a monochrome tab preview.
 */
export function severityGlyph(c: SeverityCounts): string {
  const t = highestSeverityTone(c);
  if (t === 'err') return '!';
  if (t === 'warn') return '\u25c9'; // ◉
  return '\u2022'; // •
}

/** Convenience for the document.title format. */
export function documentTitleWithFindings(c: SeverityCounts, base = 'Aurora'): string {
  const n = totalCount(c);
  if (n <= 0) return base;
  const noun = n === 1 ? 'issue' : 'issues';
  return `${severityGlyph(c)} ${n} ${noun} \u00b7 ${base}`;
}

// Re-export the severity type so callers importing this module don't
// need a second import for the union.
export type { SecuritySeverity };
