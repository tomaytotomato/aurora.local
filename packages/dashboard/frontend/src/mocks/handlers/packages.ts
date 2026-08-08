import { http, HttpResponse } from 'msw';

import { state } from '../state';
import { createJob } from '../jobs';
import { detailFor, envFor, packageSeeds } from '../fixtures/packages';
import { liveSummary, noContent } from './shared';

export const packagesHandlers = [
  http.get('/api/packages', () =>
    HttpResponse.json(packageSeeds.map((s) => liveSummary(s.summary))),
  ),
  http.get('/api/packages/:name', ({ params }) => {
    const name = String(params.name);
    const detail = detailFor(name);
    if (!detail) return new HttpResponse(null, { status: 404 });
    // envVars carries the *specs* (key/secret/required/example/comment),
    // not values — GET .../env is the values endpoint. The real API
    // documents both on PackageDetail (see openapi.yaml); this handler
    // was missing the specs, so the Config tab had nothing to render.
    return HttpResponse.json({ ...detail, ...liveSummary(detail), envVars: envFor(name) });
  }),
  http.get('/api/packages/:name/env', ({ params, request }) => {
    const reveal = new URL(request.url).searchParams.has('reveal');
    const map: Record<string, string> = {};
    for (const spec of envFor(String(params.name))) {
      const real = spec.value ?? spec.example ?? '';
      map[spec.key] = spec.secret && !reveal ? '••••••••' : real;
    }
    return HttpResponse.json(map);
  }),
  http.put('/api/packages/:name/env', () => noContent()),
  http.post('/api/packages/:name/enable', ({ params }) => {
    const name = String(params.name);
    state.enabled.add(name);
    state.running.add(name);
    return HttpResponse.json({ jobId: createJob('enable', name).id }, { status: 202 });
  }),
  http.post('/api/packages/:name/disable', ({ params }) => {
    const name = String(params.name);
    state.enabled.delete(name);
    state.running.delete(name);
    return HttpResponse.json({ jobId: createJob('disable', name).id }, { status: 202 });
  }),
  http.post('/api/packages/:name/restart', () => noContent()),
  http.post('/api/packages/:name/upgrade', ({ params }) =>
    HttpResponse.json({ jobId: createJob('update', String(params.name)).id }, { status: 202 }),
  ),
];
