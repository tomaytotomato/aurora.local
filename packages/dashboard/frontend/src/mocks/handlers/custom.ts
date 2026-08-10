import { http, HttpResponse } from 'msw';

import type { CustomStack } from '@/api/custom';

import { state } from '../state';
import { createJob } from '../jobs';
import { validateCompose } from '../fixtures/custom';
import { noContent, nowIso } from './shared';

export const customHandlers = [
  http.get('/api/custom/stacks', () => HttpResponse.json(state.custom.stacks)),

  http.post('/api/custom/stacks/validate', async ({ request }) => {
    const { composeYaml } = (await request.json()) as { composeYaml: string };
    return HttpResponse.json(validateCompose(composeYaml));
  }),

  http.post('/api/custom/stacks', async ({ request }) => {
    const { name, composeYaml } = (await request.json()) as { name: string; composeYaml: string };
    const validation = validateCompose(composeYaml);
    if (!validation.valid) {
      return HttpResponse.json({ message: validation.errors[0]?.message ?? 'That file has problems.' }, { status: 400 });
    }
    const stack: CustomStack = {
      id: 'stack-' + Math.random().toString(36).slice(2, 8),
      name,
      // Saved, not run. A stack that has never run is a useful state.
      state: 'draft',
      composeYaml,
      createdAt: nowIso(),
      lastDeployedAt: null,
      lastJobId: null,
      containers: validation.services,
    };
    state.custom.stacks = [...state.custom.stacks, stack];
    return HttpResponse.json(stack, { status: 201 });
  }),

  http.put('/api/custom/stacks/:id', async ({ params, request }) => {
    const patch = (await request.json()) as { name?: string; composeYaml?: string };
    const stack = state.custom.stacks.find((s) => s.id === String(params.id));
    if (!stack) return new HttpResponse(null, { status: 404 });
    Object.assign(stack, patch);
    return HttpResponse.json(stack);
  }),

  http.post('/api/custom/stacks/:id/deploy', ({ params }) => {
    const stack = state.custom.stacks.find((s) => s.id === String(params.id));
    if (!stack) return new HttpResponse(null, { status: 404 });
    const job = createJob('deploy', stack.name);
    stack.lastJobId = job.id;
    stack.state = 'running';
    stack.lastDeployedAt = nowIso();
    return HttpResponse.json({ jobId: job.id }, { status: 202 });
  }),

  http.post('/api/custom/stacks/:id/stop', ({ params }) => {
    const stack = state.custom.stacks.find((s) => s.id === String(params.id));
    if (!stack) return new HttpResponse(null, { status: 404 });
    stack.state = 'stopped';
    return HttpResponse.json({ jobId: createJob('disable', stack.name).id }, { status: 202 });
  }),

  http.delete('/api/custom/stacks/:id', ({ params }) => {
    state.custom.stacks = state.custom.stacks.filter((s) => s.id !== String(params.id));
    return noContent();
  }),
];
