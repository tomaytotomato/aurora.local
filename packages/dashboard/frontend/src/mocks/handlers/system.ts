// system + mdns.

import { http, HttpResponse } from 'msw';

import { metricSamples, stateFile, systemInfo } from '../fixtures/system';
import { mdnsAliases } from '../fixtures/observability';

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

  http.get('/api/mdns/aliases', () => HttpResponse.json(mdnsAliases)),
  http.post('/api/mdns/reconcile', () => HttpResponse.json(mdnsAliases)),
];
