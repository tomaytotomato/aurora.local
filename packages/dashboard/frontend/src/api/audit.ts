// iter-30 (v0.3 followup): audit-event read surface.
//
// Endpoint: GET /api/audit/events?action=&userId=&since=&until=&limit=100
// Backend sorts newest first (ts DESC, id DESC).

import { http } from './client';

export interface AuditEvent {
  id: number;
  /** ISO-8601 UTC timestamp. */
  ts: string;
  /** Acting admin id; null for bootstrap / wizard-phase events. */
  user_id: number | null;
  /** Dotted action name (e.g. 'security.dismiss', 'onboarding.launch.finish'). */
  action: string;
  /** Optional target ref ('finding:<id>', 'job:<uuid>', …). */
  target: string | null;
  /** Optional diff blob; JSON string when present. */
  diff_json: string | null;
}

export interface AuditQuery {
  action?: string;
  userId?: number;
  since?: string;
  until?: string;
  limit?: number;
}

export const AuditApi = {
  async list(q: AuditQuery = {}): Promise<AuditEvent[]> {
    const { data } = await http.get<AuditEvent[]>('/audit/events', { params: q });
    return data;
  },
};
