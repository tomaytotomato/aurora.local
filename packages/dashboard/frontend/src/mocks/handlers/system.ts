// system + mdns.

import { http, HttpResponse } from 'msw';

import { metricSamples, stateFile, systemInfo } from '../fixtures/system';
import { mdnsAliases } from '../fixtures/observability';
import { state } from '../state';

export const systemHandlers = [
  http.get('/api/system', () => HttpResponse.json(systemInfo)),
  http.get('/api/system/metrics', ({ request }) => {
    const window = (new URL(request.url).searchParams.get('window') ?? '24h') as '1h' | '24h' | '7d';
    return HttpResponse.json(metricSamples(Date.now(), window));
  }),
  http.get('/api/system/state', () => HttpResponse.json(stateFile)),
  http.get('/api/system/caddy-root.crt', () =>
    new HttpResponse('-----BEGIN CERTIFICATE-----\nMOCKCERT\n-----END CERTIFICATE-----\n', {
      headers: { 'Content-Type': 'application/x-x509-ca-cert' },
    }),
  ),

  // Settings portability. The export deliberately carries no secrets:
  // .env values stay on the box, which is what makes the file safe to
  // keep in an ordinary backup.
  http.get('/api/system/export', () =>
    HttpResponse.json({
      version: 1,
      exportedAt: new Date().toISOString(),
      hostname: 'aurora',
      domain: state.onboarding.domain,
      enabledPackages: [...state.enabled],
      profiles: [],
      dnsMode: state.onboarding.dns_mode,
      settings: {
        notifications: state.notifications.channels.map((c) => ({
          kind: c.kind,
          name: c.name,
          target: c.target,
          events: c.events,
          enabled: c.enabled,
        })),
        backupPolicy: state.backup.policy,
        protection: state.network.protection.map((v) => ({
          vhost: v.vhost,
          rateLimit: v.rateLimit,
          geoBlock: v.geoBlock,
          botDetection: v.botDetection,
        })),
        proxyRoutes: state.proxy.routes.filter((r) => !r.managed).map((r) => ({
          subdomain: r.subdomain,
          target: r.target,
        })),
      },
    }),
  ),

  http.post('/api/system/import', async ({ request }) => {
    const preview = new URL(request.url).searchParams.has('preview');
    const payload = (await request.json()) as {
      version?: number;
      enabledPackages?: string[];
      settings?: Record<string, unknown>;
    };

    if (payload.version !== 1) {
      return HttpResponse.json(
        { message: "That file came from a different version of Aurora and can't be read." },
        { status: 400 },
      );
    }

    const applied: string[] = [];
    const skipped: string[] = [];

    const packages = payload.enabledPackages ?? [];
    if (packages.length) applied.push(`${packages.length} apps enabled`);

    const settings = payload.settings ?? {};
    for (const [key, value] of Object.entries(settings)) {
      const count = Array.isArray(value) ? value.length : 1;
      applied.push(`${key} (${count})`);
    }
    // Secrets were never in the file, so they always need doing by hand.
    skipped.push('secrets — those never leave the box, so each app needs its .env filled in again');

    if (!preview) {
      state.enabled = new Set(packages);
      state.onboarding.enabled_packages = packages;
    }

    return HttpResponse.json({ applied, skipped, preview });
  }),

  http.get('/api/mdns/aliases', () => HttpResponse.json(mdnsAliases)),
  http.post('/api/mdns/reconcile', () => HttpResponse.json(mdnsAliases)),
];
