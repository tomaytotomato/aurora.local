import { http, HttpResponse } from 'msw';

import type { User, UserRole } from '@/api/users';

import { state } from '../state';
import { noContent, nowIso } from './shared';

export const usersHandlers = [
  http.get('/api/users', () => HttpResponse.json(state.users)),
  http.post('/api/users', async ({ request }) => {
    const body = (await request.json()) as { username: string; role: UserRole; password: string };
    if (state.users.some((u) => u.username === body.username)) {
      return HttpResponse.json({ message: 'That username is already taken.' }, { status: 409 });
    }
    const user: User = {
      id: 'user-' + Math.random().toString(36).slice(2, 8),
      username: body.username,
      role: body.role,
      createdAt: nowIso(),
      lastLoginAt: null,
      passkeyEnrolled: false,
    };
    state.users = [...state.users, user];
    return HttpResponse.json(user, { status: 201 });
  }),
  http.patch('/api/users/:id', async ({ params, request }) => {
    const patch = (await request.json()) as { role?: UserRole };
    const user = state.users.find((u) => u.id === String(params.id));
    if (!user) return new HttpResponse(null, { status: 404 });
    if (patch.role) user.role = patch.role;
    return HttpResponse.json(user);
  }),
  http.delete('/api/users/:id', ({ params }) => {
    const id = String(params.id);
    if (id === state.currentUserId) {
      return HttpResponse.json({ message: "You can't remove your own account." }, { status: 409 });
    }
    state.users = state.users.filter((u) => u.id !== id);
    return noContent();
  }),
];
