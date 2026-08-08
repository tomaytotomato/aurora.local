import { http, HttpResponse } from 'msw';

import type { ServiceStatus } from '@/api/services';

import { state } from '../state';
import { createJob } from '../jobs';
import { sseResponse } from '../sse';
import { servicesStatus } from '../fixtures/services';
import { nowIso } from './shared';

/** Nudge the "starting" services toward running so the stream animates. */
function progress(services: ServiceStatus[], tick: number): ServiceStatus[] {
  if (tick < 2) return services;
  const advance = (s: ServiceStatus): ServiceStatus => ({
    ...s,
    state: s.state === 'starting' || s.state === 'not-started' ? 'running' : s.state,
    detail: s.state === 'starting' ? null : s.detail,
    children: s.children?.map(advance),
  });
  return services.map(advance);
}

export const servicesHandlers = [
  http.get('/api/services/status', () => HttpResponse.json(servicesStatus(nowIso()))),
  http.get('/api/services/status/stream', () =>
    sseResponse((emit) => {
      let tick = 0;
      const send = () => {
        const base = servicesStatus(nowIso());
        emit({ event: 'service-status', data: JSON.stringify({ ...base, services: progress(base.services, tick) }) });
        tick += 1;
      };
      send();
      const timer = setInterval(send, 3_000);
      return () => clearInterval(timer);
    }),
  ),
  http.post('/api/services/:package/start', ({ params }) => {
    const name = String(params.package);
    state.running.add(name);
    // job_id is now a real job id, so the caller can stream the start log
    // instead of watching a spinner and guessing.
    const job = createJob('start', name);
    return HttpResponse.json({ job_id: job.id, packages: [name], started_at: nowIso() }, { status: 202 });
  }),
];
