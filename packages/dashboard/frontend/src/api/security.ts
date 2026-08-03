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

  /** Restore a previously dismissed finding. */
  async restore(id: string): Promise<void> {
    await http.delete(`/security/findings/${encodeURIComponent(id)}/dismiss`);
  },
};
