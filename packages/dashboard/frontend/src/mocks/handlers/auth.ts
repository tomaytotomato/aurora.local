import { http, HttpResponse } from 'msw';

import { state } from '../state';
import { noContent } from './shared';

export const authHandlers = [
  http.get('/api/auth/session', () => HttpResponse.json(state.session)),
  http.post('/api/auth/login', async ({ request }) => {
    const { username } = (await request.json()) as { username: string; password: string };
    state.session = { ...state.session, authenticated: true, username: username || 'admin' };
    return HttpResponse.json(state.session);
  }),
  http.post('/api/auth/logout', () => {
    state.session = { ...state.session, authenticated: false, username: null };
    return noContent();
  }),
];
