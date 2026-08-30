import { http } from './client';

export interface SystemCapabilities {
  metrics: boolean;
  /**
   * iter-3 P1b: gates /security. False in v0.2.x — the real rules engine
   * (host + container + secret audits) lands with milestone M4. While
   * false, SecurityPosture.vue renders the honest empty-state view and
   * the sidebar hides the nav link.
   */
  securityScanner?: boolean;
  /**
   * Gates /backup. False until the backend can talk to Kopia's server
   * API; while false the nav entry hides, on the same principle as
   * securityScanner — a page that cannot be honest should not be
   * reachable.
   */
  backup?: boolean;
  /** Gates /disks. False until the backend can read smartctl and the
   * mergerfs/snapraid state. */
  disks?: boolean;
  /** Gates the Notifications card on Settings. */
  notifications?: boolean;
  /** Gates the Addresses card on Settings. */
  proxy?: boolean;
  /** Gates /apps/custom and its section-nav entry. */
  customStacks?: boolean;
}

export interface BuildInfo {
  /** Release tag or 'dev'. Null when the image was built without stamps. */
  version: string | null;
  /** Git commit the image was built from. */
  revision: string | null;
  /** ISO timestamp of the build. */
  builtAt: string | null;
}

export interface SystemInfo {
  build?: BuildInfo | null;
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

/**
 * A portable copy of everything the wizard asked for and everything
 * configured since. Deliberately excludes secrets: .env values stay on
 * the box, so this file is safe to keep in a normal backup.
 *
 * Dashy's encrypted config backup is the model, minus the encryption,
 * because there is nothing sensitive left in here once secrets are out.
 */
export interface SettingsExport {
  /** Schema version of this file, so a future import can migrate it. */
  version: number;
  exportedAt: string;
  hostname: string | null;
  domain: string | null;
  enabledPackages: string[];
  profiles: string[];
  dnsMode: string | null;
  /** Non-secret preferences: notification channels, backup policy, protection. */
  settings: Record<string, unknown>;
}

export interface ImportResult {
  /** What would change, or did. */
  applied: string[];
  skipped: string[];
  /** True when this was a dry run. */
  preview: boolean;
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
  /** Everything needed to set this box up again, minus the secrets. */
  async exportSettings(): Promise<SettingsExport> {
    const { data } = await http.get<SettingsExport>('/system/export');
    return data;
  },
  /**
   * Apply an exported file. `preview` first is the only sane default —
   * an import that silently enables nine packages is not something to
   * discover after the fact.
   */
  async importSettings(payload: SettingsExport, preview = true): Promise<ImportResult> {
    const { data } = await http.post<ImportResult>('/system/import', payload, {
      params: preview ? { preview: 1 } : {},
    });
    return data;
  },
};
