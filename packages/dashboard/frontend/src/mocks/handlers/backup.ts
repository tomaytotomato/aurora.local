import { http, HttpResponse } from 'msw';

import type { BackupPolicy } from '@/api/backup';

import { state } from '../state';
import { createJob } from '../jobs';

export const backupHandlers = [
  http.get('/api/backup/status', () =>
    HttpResponse.json({ ...state.backup.status, generatedAt: new Date().toISOString() }),
  ),

  http.get('/api/backup/sources', () => HttpResponse.json(state.backup.sources)),

  http.patch('/api/backup/sources/:id', async ({ params, request }) => {
    const { enabled } = (await request.json()) as { enabled: boolean };
    const source = state.backup.sources.find((s) => s.id === String(params.id));
    if (!source) return new HttpResponse(null, { status: 404 });
    source.enabled = enabled;
    return HttpResponse.json(source);
  }),

  http.get('/api/backup/snapshots', ({ request }) => {
    const sourceId = new URL(request.url).searchParams.get('sourceId');
    const rows = sourceId
      ? state.backup.snapshots.filter((s) => s.sourceId === sourceId)
      : state.backup.snapshots;
    return HttpResponse.json(rows);
  }),

  http.post('/api/backup/snapshots', () =>
    HttpResponse.json({ jobId: createJob('backup', null).id }, { status: 202 }),
  ),

  http.post('/api/backup/snapshots/:id/restore', ({ params }) => {
    const id = String(params.id);
    if (!state.backup.snapshots.some((s) => s.id === id)) {
      return new HttpResponse(null, { status: 404 });
    }
    return HttpResponse.json({ jobId: createJob('restore', id).id }, { status: 202 });
  }),

  http.get('/api/backup/policy', () => HttpResponse.json(state.backup.policy)),

  http.put('/api/backup/policy', async ({ request }) => {
    const patch = (await request.json()) as Partial<BackupPolicy>;
    state.backup.policy = { ...state.backup.policy, ...patch };
    return HttpResponse.json(state.backup.policy);
  }),
];
