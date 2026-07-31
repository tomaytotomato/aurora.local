import { http } from './client';

export interface SystemInfo {
  hostname: string;
  domain: string;
  lanIp: string;
  distro: string;
  kernel: string;
  uptimeSeconds: number;
  cpuCount: number;
  memTotalBytes: number;
  memUsedBytes: number;
  diskTotalBytes: number;
  diskUsedBytes: number;
  dockerVersion: string;
  containerCount: number;
}

export interface MetricSample {
  ts: number;
  cpuPct: number;
  memPct: number;
  diskPct: number;
  containers: number;
}

export interface StateFile {
  bootstrapVersion: number;
  hostname: string;
  domain: string;
  installedAt: string;
  enabled: string[];
  profiles: string[];
}

export const SystemApi = {
  async info(): Promise<SystemInfo> {
    const { data } = await http.get<SystemInfo>('/system');
    return data;
  },
  async metrics(window: '1h' | '24h' | '7d' = '24h'): Promise<MetricSample[]> {
    const { data } = await http.get<MetricSample[]>('/system/metrics', {
      params: { window },
    });
    return data;
  },
  async state(): Promise<StateFile> {
    const { data } = await http.get<StateFile>('/system/state');
    return data;
  },
};
