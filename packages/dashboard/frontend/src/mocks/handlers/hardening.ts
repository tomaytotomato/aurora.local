import { http, HttpResponse } from 'msw';

import { initialHardening } from '../fixtures/hardening';

export const hardeningHandlers = [
  http.get('/api/security/hardening', () => HttpResponse.json(initialHardening())),
];
