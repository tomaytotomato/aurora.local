import { describe, it, expect } from 'vitest';
import type { SecurityFinding } from '@/api/security';
import {
  countBySeverity,
  totalCount,
  highestSeverityTone,
  severityGlyph,
  documentTitleWithFindings,
  EMPTY_COUNTS,
} from './severity';

// iter-35: pure-function coverage for the severity helpers extracted
// from Sidebar. Keeps the arithmetic pinned so future rules that
// introduce a new severity (e.g. 'critical') don't silently regress
// the badge tone.

function f(id: string, severity: string): SecurityFinding {
  return { id, severity, title: 't', description: 'd', remediationUrl: null };
}

describe('severity helpers', () => {
  it('countBySeverity buckets a mixed list', () => {
    const got = countBySeverity([
      f('a', 'high'),
      f('b', 'high'),
      f('c', 'medium'),
      f('d', 'low'),
      f('e', 'low'),
      f('f', 'low'),
    ]);
    expect(got).toEqual({ high: 2, medium: 1, low: 3, other: 0 });
  });

  it('countBySeverity puts unknown severities in the other bucket', () => {
    const got = countBySeverity([f('a', 'critical'), f('b', 'info')]);
    expect(got.other).toBe(2);
    expect(got.high + got.medium + got.low).toBe(0);
  });

  it('countBySeverity accepts an empty list', () => {
    expect(countBySeverity([])).toEqual(EMPTY_COUNTS);
  });

  it('totalCount adds all buckets', () => {
    expect(totalCount({ high: 1, medium: 2, low: 3, other: 4 })).toBe(10);
    expect(totalCount(EMPTY_COUNTS)).toBe(0);
  });

  it('highestSeverityTone respects precedence high > medium > low', () => {
    expect(highestSeverityTone({ high: 1, medium: 1, low: 1, other: 1 })).toBe('err');
    expect(highestSeverityTone({ high: 0, medium: 1, low: 1, other: 0 })).toBe('warn');
    expect(highestSeverityTone({ high: 0, medium: 0, low: 1, other: 0 })).toBe('info');
    // Empty maps to info — the badge won't render anyway (guarded on
    // totalCount > 0), so the fallback tone is a defensive default.
    expect(highestSeverityTone(EMPTY_COUNTS)).toBe('info');
    // Unknown-only ⇒ info (not high) so a 'critical' rule doesn't
    // hijack the tone until we teach the FE about it.
    expect(highestSeverityTone({ high: 0, medium: 0, low: 0, other: 5 })).toBe('info');
  });

  it('severityGlyph maps precedence to !/◉/•', () => {
    expect(severityGlyph({ high: 1, medium: 0, low: 0, other: 0 })).toBe('!');
    expect(severityGlyph({ high: 0, medium: 1, low: 0, other: 0 })).toBe('\u25c9');
    expect(severityGlyph({ high: 0, medium: 0, low: 1, other: 0 })).toBe('\u2022');
    expect(severityGlyph(EMPTY_COUNTS)).toBe('\u2022');
  });

  it('documentTitleWithFindings renders the tab preview', () => {
    expect(documentTitleWithFindings(EMPTY_COUNTS)).toBe('Aurora');
    expect(documentTitleWithFindings({ high: 1, medium: 0, low: 0, other: 0 }))
      .toBe('! 1 issue \u00b7 Aurora');
    expect(documentTitleWithFindings({ high: 0, medium: 3, low: 2, other: 0 }))
      .toBe('\u25c9 5 issues \u00b7 Aurora');
    expect(documentTitleWithFindings({ high: 0, medium: 0, low: 1, other: 0 }))
      .toBe('\u2022 1 issue \u00b7 Aurora');
    // Base is overridable so the same helper can be reused for a
    // future 'Aurora \u2014 hostname' pattern.
    expect(documentTitleWithFindings({ high: 2, medium: 0, low: 0, other: 0 }, 'Aurora | homelab'))
      .toBe('! 2 issues \u00b7 Aurora | homelab');
  });
});
