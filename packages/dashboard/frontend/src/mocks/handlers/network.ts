import { http, HttpResponse } from 'msw';

import type { EgressMode, ProtectionPatch } from '@/api/network';

import { state } from '../state';
import { createJob } from '../jobs';
import { networkFor } from '../fixtures/network';

export const networkHandlers = [
  http.get('/api/packages/:name/network', ({ params }) => {
    const pkg = String(params.name);
    return HttpResponse.json(state.network.byPackage[pkg] ?? networkFor(pkg));
  }),

  http.put('/api/packages/:name/network', async ({ params, request }) => {
    const pkg = String(params.name);
    const { mode } = (await request.json()) as { mode: EgressMode };
    const current = state.network.byPackage[pkg] ?? networkFor(pkg);

    if (current.locked) {
      return HttpResponse.json({ message: current.lockedReason }, { status: 409 });
    }

    state.network.byPackage[pkg] = {
      ...current,
      mode,
      egressIp: mode === 'vpn' ? '185.107.56.212' : '81.132.44.19',
      egressCountry: mode === 'vpn' ? 'NL' : 'GB',
    };

    // Containers restart, so this is a job rather than a silent PUT.
    return HttpResponse.json({ jobId: createJob('restart', pkg).id }, { status: 202 });
  }),

  http.get('/api/protection', () => HttpResponse.json(state.network.protection)),

  http.put('/api/protection/:vhost', async ({ params, request }) => {
    const patch = (await request.json()) as ProtectionPatch;
    const row = state.network.protection.find((v) => v.vhost === String(params.vhost));
    if (!row) return new HttpResponse(null, { status: 404 });
    Object.assign(row, patch);
    return HttpResponse.json(row);
  }),
];
