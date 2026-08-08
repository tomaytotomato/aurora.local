// System / host fixtures: the box facts, coarse metric samples, and the
// parsed .state.yml. Numbers chosen to look like a modest homelab NUC.

import type { OnboardingEnv } from '@/api/onboarding';
import type { MetricSample, StateFile, SystemInfo } from '@/api/system';

const GIB = 1024 ** 3;

export const systemInfo: SystemInfo = {
  hostname: 'aurora',
  domain: 'aurora.local',
  lanIp: '192.168.1.10',
  distro: 'Debian GNU/Linux 12 (bookworm)',
  kernel: '6.1.0-18-amd64',
  uptimeSeconds: 6 * 24 * 3600 + 4 * 3600,
  cpuCount: 4,
  memTotalBytes: 16 * GIB,
  memUsedBytes: Math.round(6.4 * GIB),
  diskTotalBytes: 512 * GIB,
  diskUsedBytes: Math.round(214 * GIB),
  dockerVersion: '27.3.1',
  containerCount: 11,
  capabilities: {
    metrics: true,
    securityScanner: true,
    backup: true,
  },
};

export const onboardingEnv: OnboardingEnv = {
  hostname: 'aurora',
  lanIp: '192.168.1.10',
  distro: 'Debian GNU/Linux 12 (bookworm)',
  kernel: '6.1.0-18-amd64',
  dockerVersion: '27.3.1',
  cpu: {
    model: 'Intel(R) Core(TM) i5-1340P',
    threads: 4,
    cores: 4,
    sockets: 1,
    mhz: 2200,
    load1: 0.42,
  },
  memory: {
    MemTotal: 16 * GIB,
    MemAvailable: Math.round(9.6 * GIB),
    MemFree: Math.round(2.1 * GIB),
  },
  disks: [
    { device: '/dev/nvme0n1p2', mount: '/', fstype: 'ext4', total_bytes: 512 * GIB, free_bytes: 298 * GIB, used_bytes: 214 * GIB },
  ],
  gpu: { present: false, vendor: null, model: null },
};

export const stateFile: StateFile = {
  bootstrapVersion: 3,
  hostname: 'aurora',
  domain: 'aurora.local',
  installedAt: '2026-07-31T09:12:00Z',
  enabled: ['core', 'privacy', 'media', 'monitoring'],
  profiles: [],
};

/**
 * Generate a plausible 24h series of coarse host samples. `now` is passed
 * in so the mock never calls Date.now() at module load (keeps fixtures
 * deterministic and side-effect free at import time).
 */
export function metricSamples(now: number, window: '1h' | '24h' | '7d'): MetricSample[] {
  const spanMs = window === '1h' ? 3600_000 : window === '7d' ? 7 * 86_400_000 : 86_400_000;
  const points = 48;
  const stepMs = spanMs / points;
  const out: MetricSample[] = [];
  for (let i = 0; i < points; i++) {
    const ts = now - spanMs + i * stepMs;
    // Gentle sine waves offset per metric so the charts read as "alive".
    const wave = (phase: number) => 0.5 + 0.4 * Math.sin(i / 6 + phase);
    out.push({
      ts,
      cpuPct: Math.round(wave(0) * 45 + 8),
      memPct: Math.round(wave(1.5) * 25 + 38),
      diskPct: 42,
      containers: 11,
    });
  }
  return out;
}
