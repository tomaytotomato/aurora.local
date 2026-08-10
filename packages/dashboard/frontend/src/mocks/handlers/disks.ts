import { http, HttpResponse } from 'msw';

import { state } from '../state';
import { createJob } from '../jobs';
import { smartFor } from '../fixtures/disks';

export const disksHandlers = [
  http.get('/api/disks', () => HttpResponse.json(state.disks.disks)),

  http.get('/api/disks/pool', () => HttpResponse.json(state.disks.pool)),

  http.get('/api/disks/parity', () => HttpResponse.json(state.disks.parity)),

  http.post('/api/disks/parity/sync', () =>
    HttpResponse.json({ jobId: createJob('parity-sync', null).id }, { status: 202 }),
  ),

  http.post('/api/disks/parity/scrub', () =>
    HttpResponse.json({ jobId: createJob('parity-scrub', null).id }, { status: 202 }),
  ),

  http.get('/api/disks/:id/smart', ({ params }) => {
    const id = String(params.id);
    if (!state.disks.disks.some((d) => d.id === id)) {
      return new HttpResponse(null, { status: 404 });
    }
    return HttpResponse.json(smartFor(id));
  }),
];
