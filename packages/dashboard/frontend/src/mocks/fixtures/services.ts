// Service-status fixtures. Rendered as the Done-page checklist and the
// dashboard "Services" card, and streamed over /services/status/stream.
//
// The set deliberately covers every ServiceState so you can style each
// row: a healthy core, a still-starting media parent with mixed children,
// a needs-config privacy, and a failed monitoring sub-probe.

import type { ServiceStatus, ServicesStatusResponse } from '@/api/services';

export function servicesStatus(nowIso: string): ServicesStatusResponse {
  const services: ServiceStatus[] = [
    {
      package: 'core',
      container: 'aurora-caddy',
      state: 'running',
      reason: null,
      detail: 'HTTPS listener healthy',
      open_url: 'https://aurora.local',
      priority: 0,
      probed_ms: 12,
    },
    {
      package: 'privacy',
      container: 'aurora-privacy-adguard',
      state: 'needs-config',
      reason: 'AdGuard setup wizard not completed',
      detail: 'Open the UI to finish first-run setup',
      open_url: 'https://adguard.aurora.local',
      priority: 1,
      probed_ms: 34,
    },
    {
      package: 'media',
      container: null,
      state: 'starting',
      reason: null,
      detail: 'Pulling images (3 of 7)',
      open_url: null,
      priority: 2,
      probed_ms: 8,
      children: [
        { package: 'prowlarr', container: 'aurora-media-prowlarr', state: 'running', reason: null, detail: null, open_url: 'https://prowlarr.aurora.local', priority: 0, probed_ms: 21 },
        { package: 'sonarr', container: 'aurora-media-sonarr', state: 'starting', reason: null, detail: 'Waiting for healthcheck', open_url: 'https://sonarr.aurora.local', priority: 1, probed_ms: 19 },
        { package: 'radarr', container: 'aurora-media-radarr', state: 'not-started', reason: null, detail: null, open_url: null, priority: 2, probed_ms: 5 },
      ],
    },
    {
      package: 'monitoring',
      container: 'aurora-monitoring-grafana',
      state: 'running',
      reason: null,
      detail: 'Grafana up',
      open_url: 'https://grafana.aurora.local',
      priority: 3,
      probed_ms: 40,
      children: [
        { package: 'grafana', container: 'aurora-monitoring-grafana', state: 'running', reason: null, detail: null, open_url: 'https://grafana.aurora.local', priority: 0, probed_ms: 40 },
        { package: 'uptime-kuma', container: 'aurora-monitoring-kuma', state: 'failed', reason: 'Container exited (code 1)', detail: 'Check logs: aurora-monitoring-kuma', open_url: null, priority: 1, probed_ms: 15 },
      ],
    },
  ];
  return { generated_at: nowIso, services };
}
