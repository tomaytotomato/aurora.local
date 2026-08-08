import { http, HttpResponse } from 'msw';

import type { JobKind, JobState } from '@/api/jobs';

import { state } from '../state';
import { finishJob, nextLine, publicJob } from '../jobs';
import { sseResponse } from '../sse';

export const jobsHandlers = [
  http.get('/api/jobs', ({ request }) => {
    const url = new URL(request.url);
    const wantState = url.searchParams.get('state') as JobState | null;
    const wantKind = url.searchParams.get('kind') as JobKind | null;
    const rows = Object.values(state.jobs)
      .filter((j) => (wantState ? j.state === wantState : true))
      .filter((j) => (wantKind ? j.kind === wantKind : true))
      .map(publicJob)
      // Newest first, matching what the backend will do.
      .sort((a, b) => b.startedAt.localeCompare(a.startedAt));
    return HttpResponse.json(rows);
  }),

  http.get('/api/jobs/:id', ({ params }) => {
    const job = state.jobs[String(params.id)];
    if (!job) return new HttpResponse(null, { status: 404 });
    return HttpResponse.json(publicJob(job));
  }),

  // SSE: replay whatever the job has already produced, then stream the
  // rest of its script and finish with a terminal `done`.
  http.get('/api/jobs/:id/stream', ({ params }) => {
    const job = state.jobs[String(params.id)];
    if (!job) return new HttpResponse(null, { status: 404 });

    return sseResponse((emit) => {
      // Catch a late subscriber up on everything it missed.
      for (const line of job.tail) emit({ event: 'log', data: line });

      if (job.state === 'success' || job.state === 'failed') {
        emit({ event: 'done', data: JSON.stringify(publicJob(job)) });
        return;
      }

      const timer = setInterval(() => {
        const line = nextLine(job);
        if (line !== null) {
          emit({ event: 'log', data: line });
          return;
        }
        finishJob(job);
        emit({ event: 'done', data: JSON.stringify(publicJob(job)) });
        clearInterval(timer);
      }, job.script.intervalMs);

      return () => clearInterval(timer);
    });
  }),
];
