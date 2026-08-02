import { http } from './client';

// Iter-2: per-package live status probes rendered as the Done page checklist.
// Polled every 5s while /onboarding/done is mounted.

export type ServiceState =
  | 'running'
  | 'needs-config'
  | 'failed'
  | 'not-started'
  | 'starting';

export interface ServiceStatus {
  package: string;
  container: string | null;
  state: ServiceState;
  reason: string | null;
  detail: string | null;
  open_url: string | null;
  priority: number;
  probed_ms: number;
}

export interface ServicesStatusResponse {
  generated_at: string;
  services: ServiceStatus[];
}

/** Response of POST /api/services/{package}/start — 202 with the created job. */
export interface ServiceStartResponse {
  job_id: string;
  packages: string[];
  started_at: string;
}

export const ServicesApi = {
  async status(): Promise<ServicesStatusResponse> {
    const res = await http.get<ServicesStatusResponse>('/services/status');
    return res.data;
  },
  /**
   * Kick off a single-package launch after onboarding is complete. Sibling
   * of POST /api/onboarding/launch (which is wizard-scoped and 409s
   * post-complete). See UX_SPEC_DASHBOARD.md §2.1.
   *
   * iter-3 B4: extend the axios timeout for this call specifically. The
   * backend returns 202 fast, but the whole HTTP round-trip can bounce
   * through session setup + wizard-status checks. Give it 30 s so we
   * don't hit the client default (15 s) and misreport a real 202 as an
   * axios timeout → red row.
   */
  async start(pkg: string): Promise<ServiceStartResponse> {
    const res = await http.post<ServiceStartResponse>(
      `/services/${encodeURIComponent(pkg)}/start`,
      undefined,
      { timeout: 30_000 },
    );
    return res.data;
  },
};
