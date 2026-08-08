import { http, HttpResponse } from 'msw';

import { sseResponse } from '../sse';
import { containerLogs, containers, recentEvents } from '../fixtures/observability';

export const containersHandlers = [
  http.get('/api/containers', ({ request }) => {
    const pkg = new URL(request.url).searchParams.get('package');
    const rows = pkg ? containers.filter((c) => c.labels['aurora.package'] === pkg) : containers;
    return HttpResponse.json(rows);
  }),
  http.get('/api/containers/events', () => HttpResponse.json(recentEvents)),
  http.get('/api/containers/events/stream', () =>
    sseResponse((emit) => {
      for (const ev of recentEvents) emit({ event: 'container-event', data: JSON.stringify(ev) });
      const actions = ['start', 'health:healthy', 'restart', 'health:unhealthy'];
      let i = 0;
      const timer = setInterval(() => {
        emit({
          event: 'container-event',
          data: JSON.stringify({ ts: Date.now(), container: 'aurora-media-sonarr', action: actions[i % actions.length] }),
        });
        i += 1;
      }, 4_000);
      return () => clearInterval(timer);
    }),
  ),
  http.get('/api/containers/:id/logs', ({ params, request }) => {
    const tail = Number(new URL(request.url).searchParams.get('tail') ?? 200);
    return HttpResponse.json(containerLogs(String(params.id), tail));
  }),
];
