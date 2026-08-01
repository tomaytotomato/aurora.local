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

export const ServicesApi = {
  async status(): Promise<ServicesStatusResponse> {
    const res = await http.get<ServicesStatusResponse>('/services/status');
    return res.data;
  },
};
