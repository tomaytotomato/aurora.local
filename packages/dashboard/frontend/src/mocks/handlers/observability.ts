// metrics + security + audit: three small read-mostly domains that share
// one fixture file, so they share one handler file too.

import { http, HttpResponse } from 'msw';

import {
  auditEvents,
  dismissals,
  metricBuckets,
  metricKeys,
  securityFindings,
} from '../fixtures/observability';
import { noContent } from './shared';

const metrics = [
  http.get('/api/metrics/keys', ({ request }) => {
    const prefix = new URL(request.url).searchParams.get('prefix') ?? undefined;
    return HttpResponse.json(metricKeys(prefix));
  }),
  http.get('/api/metrics/last24h', ({ request }) => {
    const bucket = Number(new URL(request.url).searchParams.get('bucketMinutes') ?? 5);
    return HttpResponse.json(metricBuckets(Date.now(), bucket));
  }),
];

const dismissedIds = new Set(dismissals.map((d) => d.finding_id));

const security = [
  http.get('/api/security/findings', ({ request }) => {
    const includeDismissed = new URL(request.url).searchParams.has('includeDismissed');
    const rows = includeDismissed
      ? securityFindings
      : securityFindings.filter((f) => !dismissedIds.has(f.id));
    return HttpResponse.json(rows);
  }),
  http.post('/api/security/findings/:id/dismiss', ({ params }) => {
    dismissedIds.add(String(params.id));
    return noContent();
  }),
  http.delete('/api/security/findings/:id/dismiss', ({ params }) => {
    dismissedIds.delete(String(params.id));
    return noContent();
  }),
  http.get('/api/security/dismissals', () => HttpResponse.json(dismissals)),
];

const audit = [http.get('/api/audit/events', () => HttpResponse.json(auditEvents))];

export const observabilityHandlers = [...metrics, ...security, ...audit];
