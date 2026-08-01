import { http } from './client';

export interface SystemCapabilities {
  metrics: boolean;
}

export interface SystemInfo {
  hostname: string | null;
  domain: string | null;
  lanIp: string | null;
  distro: string | null;
  kernel: string | null;
  uptimeSeconds: number | null;
  cpuCount: number | null;
  memTotalBytes: number | null;
  memUsedBytes: number | null;
  diskTotalBytes: number | null;
  diskUsedBytes: number | null;
  dockerVersion: string | null;
  containerCount: number | null;
  capabilities: SystemCapabilities;
}

export interface MetricSample {
  ts: number;
  cpuPct: number;
  memPct: number;
  diskPct: number;
  containers: number;
}

export interface StateFile {
  bootstrapVersion: number | null;
  hostname: string | null;
  domain: string | null;
  installedAt: string | null;
  enabled: string[];
  profiles: string[];
}

export const SystemApi = {
  async info(): Promise<SystemInfo> {
    const { data } = await http.get<SystemInfo>('/system');
    return data;
  },
  async metrics(window: '1h' | '24h' | '7d' = '24h'): Promise<MetricSample[]> {
    // iter-1: no metrics backend yet. Gate this call on
    // SystemInfo.capabilities.metrics before invoking; see DashboardHome.vue.
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
