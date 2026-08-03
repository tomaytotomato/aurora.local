// B4 (v0.3, iter-14): SecurityFindings read surface.
//
// Payload shape mirrors SecurityFinding (backend record) exactly.
// severity is a lowercase string constant so a future 'critical' or
// 'info' can be added without a schema change; the UI maps unknown
// severities to the neutral Badge tone.

import { http } from './client';

export type SecuritySeverity = 'high' | 'medium' | 'low' | (string & {});

export interface SecurityFinding {
  id: string;
  severity: SecuritySeverity;
  title: string;
  description: string;
  /** In-app route or external URL; may be null. */
  remediationUrl: string | null;
}

export const SecurityApi = {
  /**
   * Fetch all findings the backend rules currently emit. Backend sorts
   * HIGH → MEDIUM → LOW then by id so the response is UI-render-ready.
   */
  async findings(includeDismissed = false): Promise<SecurityFinding[]> {
    const { data } = await http.get<SecurityFinding[]>('/security/findings', {
      params: includeDismissed ? { includeDismissed: true } : {},
    });
    return data;
  },

  /**
   * B4-followup (iter-23): dismiss / snooze a finding.
   * @param id      finding id from the rule engine.
   * @param days    1..365 to snooze for N days; omitted / 0 = permanent.
   * @param reason  optional free-text explanation; truncated at 512 chars.
   */
  async dismiss(id: string, days?: number, reason?: string): Promise<void> {
    await http.post(`/security/findings/${encodeURIComponent(id)}/dismiss`, {
      days,
      reason,
    });
  },

  /**
   * B4-followup (iter-25): list all currently suppressed findings for a
   * settings-side management view. Rows come straight from the
   * security_dismissal table — shape is deliberately kept as a plain
   * object so a future schema change (e.g. per-user snooze) doesn't
   * force a wire contract rewrite.
   */
  async listDismissals(): Promise<DismissalRow[]> {
    const { data } = await http.get<DismissalRow[]>('/security/dismissals');
    return data;
  },

  /** Restore a previously dismissed finding. */
  async restore(id: string): Promise<void> {
    await http.delete(`/security/findings/${encodeURIComponent(id)}/dismiss`);
  },
};

/**
 * Row shape returned by GET /api/security/dismissals. Matches the
 * backend LinkedHashMap key order verbatim so date parsing on the FE
 * doesn't drift from the SQL representation.
 */
export interface DismissalRow {
  finding_id: string;
  dismissed_at: string;
  expires_at: string | null;
  reason: string | null;
}
