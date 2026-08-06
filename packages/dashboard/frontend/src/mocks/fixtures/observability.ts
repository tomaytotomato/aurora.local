// Fixtures for the read-only observability surfaces: security findings,
// audit log, mDNS aliases, containers, and metric series.

import type { AuditEvent } from '@/api/audit';
import type { ContainerEventItem, ContainerInfo, ContainerLogsResponse } from '@/api/containers';
import type { DismissalRow, SecurityFinding } from '@/api/security';
import type { MdnsAliasPayload } from '@/api/mdns';
import type { MetricBucket } from '@/api/metrics';

export const securityFindings: SecurityFinding[] = [
  {
    id: 'weak-admin-password',
    severity: 'high',
    title: 'Admin password is weak',
    description: 'The dashboard admin password is shorter than 12 characters. Anyone on the LAN can brute-force it.',
    remediationUrl: '/settings',
  },
  {
    id: 'docker-socket-exposed',
    severity: 'high',
    title: 'Docker socket mounted into a container',
    description: 'A container has /var/run/docker.sock bind-mounted, giving it root-equivalent control of the host.',
    remediationUrl: null,
  },
  {
    id: 'unpinned-image-tags',
    severity: 'medium',
    title: '3 images use floating tags',
    description: 'Some packages reference :latest instead of a pinned digest. Rebuilds may pull a different image without warning.',
    remediationUrl: null,
  },
  {
    id: 'tls-not-trusted',
    severity: 'low',
    title: 'Caddy root CA not installed on this device',
    description: 'HTTPS shows a warning until you trust the Aurora root certificate.',
    remediationUrl: '/settings',
  },
];

export const dismissals: DismissalRow[] = [
  {
    finding_id: 'unpinned-image-tags',
    dismissed_at: '2026-08-04T18:22:00Z',
    expires_at: '2026-08-11T18:22:00Z',
    reason: 'Accepted for the media stack until the next pin sweep.',
  },
];

export const auditEvents: AuditEvent[] = [
  { id: 42, ts: '2026-08-06T08:40:11Z', user_id: 1, action: 'package.enable', target: 'package:media', diff_json: null },
  { id: 41, ts: '2026-08-06T08:39:02Z', user_id: 1, action: 'security.dismiss', target: 'finding:unpinned-image-tags', diff_json: '{"days":7}' },
  { id: 40, ts: '2026-08-06T08:12:55Z', user_id: 1, action: 'auth.login', target: null, diff_json: null },
  { id: 39, ts: '2026-08-05T21:03:14Z', user_id: null, action: 'onboarding.launch.finish', target: 'job:8f2c', diff_json: null },
  { id: 38, ts: '2026-08-05T21:00:41Z', user_id: null, action: 'onboarding.complete', target: null, diff_json: null },
];

export const mdnsAliases: MdnsAliasPayload = {
  total: 5,
  up: 4,
  failed: 1,
  aliases: [
    { alias: 'aurora.local', label: 'aurora', pkg: 'core', source: 'manifest', state: 'up', targetIp: '192.168.1.10', publishedAt: '2026-08-05T21:01:00Z', error: null },
    { alias: 'adguard.aurora.local', label: 'adguard', pkg: 'privacy', source: 'caddy', state: 'up', targetIp: '192.168.1.10', publishedAt: '2026-08-05T21:01:02Z', error: null },
    { alias: 'grafana.aurora.local', label: 'grafana', pkg: 'monitoring', source: 'manifest', state: 'up', targetIp: '192.168.1.10', publishedAt: '2026-08-05T21:01:03Z', error: null },
    { alias: 'uptime.aurora.local', label: 'uptime', pkg: 'monitoring', source: 'manifest', state: 'up', targetIp: '192.168.1.10', publishedAt: '2026-08-05T21:01:03Z', error: null },
    { alias: 'sonarr.aurora.local', label: 'sonarr', pkg: 'media', source: 'caddy', state: 'failed', targetIp: '192.168.1.10', publishedAt: null, error: 'avahi: name collision' },
  ],
};

export const containers: ContainerInfo[] = [
  { id: 'c0affe01', names: ['aurora-caddy'], image: 'caddy:2.8', state: 'running', status: 'Up 6 days', service: 'caddy', labels: { 'aurora.package': 'core' } },
  { id: 'c0affe02', names: ['aurora-privacy-adguard'], image: 'adguard/adguardhome:v0.107', state: 'running', status: 'Up 6 days', service: 'adguard', labels: { 'aurora.package': 'privacy' } },
  { id: 'c0affe03', names: ['aurora-media-prowlarr'], image: 'lscr.io/linuxserver/prowlarr:latest', state: 'running', status: 'Up 3 minutes', service: 'prowlarr', labels: { 'aurora.package': 'media' } },
  { id: 'c0affe04', names: ['aurora-media-sonarr'], image: 'lscr.io/linuxserver/sonarr:latest', state: 'restarting', status: 'Restarting (1)', service: 'sonarr', labels: { 'aurora.package': 'media' } },
  { id: 'c0affe05', names: ['aurora-monitoring-kuma'], image: 'louislam/uptime-kuma:1', state: 'exited', status: 'Exited (1) 2 minutes ago', service: 'uptime-kuma', labels: { 'aurora.package': 'monitoring' } },
];

export const recentEvents: ContainerEventItem[] = [
  { ts: 1_754_470_000_000, container: 'aurora-media-prowlarr', action: 'start', image: 'lscr.io/linuxserver/prowlarr:latest' },
  { ts: 1_754_470_005_000, container: 'aurora-media-sonarr', action: 'health:unhealthy' },
  { ts: 1_754_470_010_000, container: 'aurora-monitoring-kuma', action: 'die', image: 'louislam/uptime-kuma:1' },
];

export function containerLogs(id: string, tail: number): ContainerLogsResponse {
  const lines = [
    { ts: '2026-08-06T08:41:01Z', stream: 'stdout' as const, line: '[Info] Starting service' },
    { ts: '2026-08-06T08:41:02Z', stream: 'stdout' as const, line: '[Info] Loading configuration from /config' },
    { ts: '2026-08-06T08:41:03Z', stream: 'stderr' as const, line: '[Warn] No API key set; generating a new one' },
    { ts: '2026-08-06T08:41:04Z', stream: 'stdout' as const, line: '[Info] Listening on 0.0.0.0:8989' },
  ];
  return { container_id: id, tail, truncated: false, lines };
}

const METRIC_KEYS = ['host.cpu.pct', 'host.mem.pct', 'host.disk.pct', 'container.count'];

export function metricKeys(prefix?: string): string[] {
  return prefix ? METRIC_KEYS.filter((k) => k.startsWith(prefix)) : METRIC_KEYS;
}

/** Deterministic 24h bucket series for a single key. */
export function metricBuckets(now: number, bucketMinutes: number): MetricBucket[] {
  const stepMs = bucketMinutes * 60_000;
  const count = Math.min(288, Math.floor((24 * 60) / bucketMinutes));
  const out: MetricBucket[] = [];
  for (let i = 0; i < count; i++) {
    const ts = now - (count - i) * stepMs;
    const base = 40 + 20 * Math.sin(i / 8);
    out.push({
      ts,
      avg: Math.round(base),
      min: Math.round(base - 6),
      max: Math.round(base + 6),
      count: bucketMinutes,
    });
  }
  return out;
}
