import { http } from './client';

// Onboarding is a stateful multi-step flow. The backend persists progress
// in SQLite so a reload doesn't drop the user back to step 1.

export type OnboardingStepId =
  | 'welcome'
  | 'admin'
  | 'domain'
  | 'packages'
  | 'secrets'
  | 'dns'
  | 'tls'
  | 'review'
  | 'done';

export interface OnboardingStatus {
  complete: boolean;
  bootstrap_mode: boolean;
  step: OnboardingStepId;
}

export interface OnboardingEnv {
  hostname: string | null;
  lanIp: string | null;
  distro: string | null;
  kernel: string | null;
  dockerVersion: string | null;
}

export interface AdminSetupPayload {
  username: string;
  password: string;
}

export interface DnsChoicePayload {
  mode: 'adguard' | 'router' | 'mdns';
}

export interface InstallPlan {
  packagesToEnable: string[];
  packagesToDisable: string[];
  vhosts: string[];
  ports: number[];
  warnings: string[];
}

export const OnboardingApi = {
  /** Public. Never 401. Used by the router guard to decide first-run redirect. */
  async status(): Promise<OnboardingStatus> {
    const { data } = await http.get<OnboardingStatus>('/onboarding/status');
    return data;
  },
  /** Public. Basic box facts safe to expose before login. */
  async env(): Promise<OnboardingEnv> {
    const { data } = await http.get<OnboardingEnv>('/onboarding/env');
    return data;
  },
  async setAdmin(p: AdminSetupPayload): Promise<void> {
    await http.post('/onboarding/admin', p);
  },
  async setDomain(domain: string): Promise<void> {
    await http.post('/onboarding/domain', { domain });
  },
  async setPackages(names: string[]): Promise<void> {
    // Backend accepts both {enabled} and {names}; send both for max compat.
    await http.post('/onboarding/packages', { enabled: names, names });
  },
  async setDns(_choice: DnsChoicePayload): Promise<void> {
    // v0.2 — DNS choice not yet persisted server-side. Silent no-op so
    // the wizard flow completes for now.
  },
  async plan(): Promise<InstallPlan> {
    // v0.2 — no real planner yet; return an empty plan so review renders.
    return { packagesToEnable: [], packagesToDisable: [], vhosts: [], ports: [], warnings: [] };
  },
  async install(): Promise<{ jobId: string }> {
    // v0.2 — install runs at bootstrap time on the host, not from Aurora.
    return { jobId: 'noop' };
  },
  async complete(): Promise<void> {
    await http.post('/onboarding/complete');
  },
  caddyRootCaUrl(): string {
    return '/api/system/caddy-root.crt';
  },
};
