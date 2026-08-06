import { http } from './client';

export type PackageStatus = 'running' | 'degraded' | 'stopped' | 'not-installed';
export type PackageCategory =
  | 'core'
  | 'privacy'
  | 'media'
  | 'storage'
  | 'productivity'
  | 'monitoring'
  | 'dev'
  | 'ai'
  | 'home-automation'
  | 'identity'
  // 'vpn' package (2026-08-06): an inbound WireGuard/OpenVPN server for
  // remote access, which is the opposite direction of 'privacy''s
  // outbound Gluetun tunnel — neither existing category fits.
  | 'network';

export interface EnvVarSpec {
  key: string;
  value?: string;
  example?: string;
  comment?: string;
  secret: boolean;
  required: boolean;
}

// iter-3 B4: shape matches what the backend Package record actually
// serialises (Jackson emits camelCase of the record fields). Prior to
// this the type claimed `status: PackageStatus` and `containers: number`
// which the wire never provided — so `p.status === 'running'` was
// always false and the dashboard header always read "0 running".
// Use `packageStatus(p)` to derive the label for the UI.
export interface PackageSummary {
  name: string;
  title?: string;
  category: PackageCategory;
  description: string;
  enabled: boolean;
  running: boolean;
  requires?: Record<string, unknown> | null;
  ports?: Array<Record<string, unknown>> | null;
  dependsOn?: string[] | null;
}

/** Derive a status label from the on-wire booleans. */
export function packageStatus(p: PackageSummary): PackageStatus {
  if (!p.enabled) return 'not-installed';
  if (p.running) return 'running';
  return 'stopped';
}

/**
 * How long the frontend should wait after clicking Start before it flips
 * a row to "Couldn't start". Comes from the manifest's
 * `requires.start_budget_seconds`; defaults to 30 s. Media declares 180 s
 * (7 containers + first-run pulls); privacy 60 s (gluetun handshake).
 */
export function startBudgetMs(p: PackageSummary | undefined | null): number {
  const raw = p?.requires?.start_budget_seconds;
  const n = typeof raw === 'number' ? raw : Number(raw);
  if (Number.isFinite(n) && n > 0) return Math.min(600, n) * 1000;
  return 30_000;
}

export interface PackageDetail extends PackageSummary {
  readme?: string;
  vhosts?: string[];
  homepageTiles?: number;
  envVars?: EnvVarSpec[];
}

export const PackagesApi = {
  async list(): Promise<PackageSummary[]> {
    const { data } = await http.get<PackageSummary[]>('/packages');
    return data;
  },
  async get(name: string): Promise<PackageDetail> {
    const { data } = await http.get<PackageDetail>(`/packages/${name}`);
    return data;
  },
  async env(name: string, reveal = false): Promise<Record<string, string>> {
    const { data } = await http.get<Record<string, string>>(`/packages/${name}/env`, {
      params: reveal ? { reveal: 1 } : {},
    });
    return data;
  },
  async setEnv(name: string, vars: Record<string, string>): Promise<void> {
    await http.put(`/packages/${name}/env`, vars);
  },
  async enable(name: string): Promise<{ jobId: string }> {
    const { data } = await http.post<{ jobId: string }>(`/packages/${name}/enable`);
    return data;
  },
  async disable(name: string): Promise<{ jobId: string }> {
    const { data } = await http.post<{ jobId: string }>(`/packages/${name}/disable`);
    return data;
  },
  async restart(name: string): Promise<void> {
    await http.post(`/packages/${name}/restart`);
  },
  async upgrade(name: string): Promise<{ jobId: string }> {
    const { data } = await http.post<{ jobId: string }>(`/packages/${name}/upgrade`);
    return data;
  },
};
