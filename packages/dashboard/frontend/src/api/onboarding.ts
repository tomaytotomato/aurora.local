import { http } from './client';

// Onboarding is a stateful multi-step flow. The backend persists progress
// in SQLite + .state.yml so a reload doesn't drop the user back to step 1.
//
// v0.2 shape: one canonical draft resource.
//   GET   /onboarding          -> full draft (hydration)
//   PATCH /onboarding          -> partial update of any draft field
//   POST  /onboarding/admin    -> one-shot bootstrap of the initial admin
//   POST  /onboarding/complete -> commit
// The legacy POST /domain and POST /packages routes still exist on the
// server for one release; new code uses PATCH.

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

export type DnsMode = 'adguard' | 'router' | 'mdns';

/** Full draft returned by GET /onboarding. Everything nullable so a fresh
 *  install can hydrate cleanly. */
export interface OnboardingDraft {
  complete: boolean;
  bootstrap_mode: boolean;
  step: OnboardingStepId;
  admin_username: string | null;
  domain: string | null;
  enabled_packages: string[];
  dns_mode: DnsMode | null;
}

/** Legacy shape kept only for the router guard's initial nav during the
 *  one-release deprecation window. Prefer {@link OnboardingDraft}. */
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
  cpu?: {
    model: string | null;
    threads: number;
    cores: number;
    sockets: number;
    mhz: number | null;
    load1: number | null;
  } | null;
  memory?: {
    MemTotal?: number;
    MemAvailable?: number;
    MemFree?: number;
  } | null;
  disks?: Array<{
    device: string;
    mount: string;
    fstype: string;
    total_bytes?: number;
    free_bytes?: number;
    used_bytes?: number;
  }> | null;
  gpu?: {
    present: boolean;
    vendor: string | null;
    model?: string | null;
  } | null;
}

export interface AdminSetupPayload {
  username: string;
  password: string;
}

/** Partial-update body. Any omitted field is a no-op server-side. */
export interface OnboardingPatch {
  domain?: string;
  enabled_packages?: string[];
  dns_mode?: DnsMode;
  step?: OnboardingStepId;
}

export interface InstallPlan {
  packagesToEnable: string[];
  packagesToDisable: string[];
  vhosts: string[];
  ports: number[];
  warnings: string[];
}

/** Result of POST /onboarding/install. v0.1 does not spawn containers —
 *  it reports which packages the operator still needs to bring up via
 *  {@code scripts/up.sh} on the host. */
export interface InstallResult {
  applied: string[];
  packages_to_start: string[];
  packages_to_stop: string[];
  host_command: string;
}

/** Wire shape of GET /onboarding/plan. Server uses snake_case. */
interface PlanWire {
  packages_to_enable: string[];
  packages_to_disable: string[];
  vhosts: string[];
  ports: number[];
  warnings: string[];
}

export const OnboardingApi = {
  /** Full draft. Router guard + store hydration use this. Public. */
  async get(): Promise<OnboardingDraft> {
    const { data } = await http.get<OnboardingDraft>('/onboarding');
    return data;
  },

  /**
   * Partial update. Server rejects with 409 if no admin exists yet or the
   * wizard has already been committed. Public otherwise (v0.1 punt — see
   * DASHBOARD_BRIEF §Post-admin unauth risk).
   */
  async patch(fields: OnboardingPatch): Promise<OnboardingDraft> {
    const { data } = await http.patch<OnboardingDraft>('/onboarding', fields);
    return data;
  },

  /** @deprecated use {@link get} instead. */
  async status(): Promise<OnboardingStatus> {
    const { data } = await http.get<OnboardingStatus>('/onboarding/status');
    return data;
  },

  /** Public. Basic box facts safe to expose before login. */
  async env(): Promise<OnboardingEnv> {
    const { data } = await http.get<OnboardingEnv>('/onboarding/env');
    return data;
  },

  /** One-shot bootstrap. Rejected with 409 after first success. */
  async setAdmin(p: AdminSetupPayload): Promise<void> {
    await http.post('/onboarding/admin', p);
  },

  async plan(): Promise<InstallPlan> {
    const { data } = await http.get<PlanWire>('/onboarding/plan');
    // Backend uses snake_case; keep the frontend's camelCase InstallPlan shape.
    return {
      packagesToEnable: data.packages_to_enable ?? [],
      packagesToDisable: data.packages_to_disable ?? [],
      vhosts: data.vhosts ?? [],
      ports: data.ports ?? [],
      warnings: data.warnings ?? [],
    };
  },

  /**
   * Speculative plan preview. Same shape as {@link plan} but scoped to a
   * caller-supplied enabled[] instead of what's currently on disk. Used by
   * the Packages step to show live resource warnings as the user toggles
   * packages, before any PATCH is committed.
   */
  async previewPlan(enabled: string[]): Promise<InstallPlan> {
    const csv = enabled.join(',');
    const { data } = await http.get<PlanWire>('/onboarding/plan', {
      params: { enabled: csv },
    });
    return {
      packagesToEnable: data.packages_to_enable ?? [],
      packagesToDisable: data.packages_to_disable ?? [],
      vhosts: data.vhosts ?? [],
      ports: data.ports ?? [],
      warnings: data.warnings ?? [],
    };
  },
  async install(): Promise<InstallResult> {
    const { data } = await http.post<InstallResult>('/onboarding/install');
    return data;
  },
  async complete(): Promise<void> {
    await http.post('/onboarding/complete');
  },
  caddyRootCaUrl(): string {
    return '/api/system/caddy-root.crt';
  },
};
