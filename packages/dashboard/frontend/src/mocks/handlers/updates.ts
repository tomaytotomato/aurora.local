import { http, HttpResponse } from 'msw';

import { state } from '../state';
import { createJob } from '../jobs';

export const updatesHandlers = [
  http.get('/api/updates', () => HttpResponse.json(Object.values(state.updates))),

  http.get('/api/updates/:name', ({ params }) => {
    const row = state.updates[String(params.name)];
    if (!row) return new HttpResponse(null, { status: 404 });
    return HttpResponse.json(row);
  }),

  // A re-check is itself a job, because querying nine registries is slow
  // enough that the operator deserves to see it happening.
  http.post('/api/updates/check', () => {
    const job = createJob('update-check', null);
    const checkedAt = new Date().toISOString();
    for (const row of Object.values(state.updates)) row.lastCheckedAt = checkedAt;
    return HttpResponse.json({ jobId: job.id }, { status: 202 });
  }),
];
