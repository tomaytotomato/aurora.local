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
  | 'identity';

export interface EnvVarSpec {
  key: string;
  value?: string;
  example?: string;
  comment?: string;
  secret: boolean;
  required: boolean;
}

export interface PackageSummary {
  name: string;
  category: PackageCategory;
  description: string;
  enabled: boolean;
  status: PackageStatus;
  containers: number;
  ports: number[];
  dependencies: string[];
}

export interface PackageDetail extends PackageSummary {
  readme: string;
  vhosts: string[];
  homepageTiles: number;
  envVars: EnvVarSpec[];
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
