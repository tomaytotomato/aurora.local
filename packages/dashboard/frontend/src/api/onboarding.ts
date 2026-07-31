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

export interface OnboardingState {
  active: boolean;
  currentStep: OnboardingStepId;
  completedSteps: OnboardingStepId[];
  admin: { username: string | null; passkeyEnrolled: boolean } | null;
  domain: string | null;
  selectedPackages: string[];
  dnsMode: 'adguard' | 'router' | 'mdns' | null;
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
  async state(): Promise<OnboardingState> {
    const { data } = await http.get<OnboardingState>('/onboarding');
    return data;
  },
  async setAdmin(p: AdminSetupPayload): Promise<void> {
    await http.post('/onboarding/admin', p);
  },
  async setDomain(domain: string): Promise<void> {
    await http.post('/onboarding/domain', { domain });
  },
  async setPackages(names: string[]): Promise<void> {
    await http.post('/onboarding/packages', { names });
  },
  async setDns(choice: DnsChoicePayload): Promise<void> {
    await http.post('/onboarding/dns', choice);
  },
  async plan(): Promise<InstallPlan> {
    const { data } = await http.get<InstallPlan>('/onboarding/plan');
    return data;
  },
  async install(): Promise<{ jobId: string }> {
    const { data } = await http.post<{ jobId: string }>('/onboarding/install');
    return data;
  },
  async complete(): Promise<void> {
    await http.post('/onboarding/complete');
  },
  caddyRootCaUrl(): string {
    return '/api/system/caddy-root.crt';
  },
};
