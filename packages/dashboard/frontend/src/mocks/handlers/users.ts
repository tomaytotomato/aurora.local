import { http, HttpResponse } from 'msw';

import type { Role, UserSummary } from '@/api/users';

import { state } from '../state';
import { noContent, nowIso } from './shared';

/**
 * Phase D's contract: admin-only mutations, PUT rather than PATCH for
 * updates, and numeric ids.
 */
export const usersHandlers = [
  http.get('/api/users', () => HttpResponse.json(state.users)),

  http.get('/api/users/:id', ({ params }) => {
    const user = state.users.find((u) => String(u.id) === String(params.id));
    if (!user) return new HttpResponse(null, { status: 404 });
    return HttpResponse.json(user);
  }),

  http.post('/api/users', async ({ request }) => {
    const body = (await request.json()) as { username: string; role: Role; password: string; tz?: string };
    if (state.users.some((u) => u.username === body.username)) {
      return HttpResponse.json({ message: 'That username is already taken.' }, { status: 409 });
    }
    const user: UserSummary = {
      id: Math.max(0, ...state.users.map((u) => u.id)) + 1,
      username: body.username,
      role: body.role,
      tz: body.tz ?? 'UTC',
      createdAt: nowIso(),
    };
    state.users = [...state.users, user];
    return HttpResponse.json(user, { status: 201 });
  }),

  http.put('/api/users/:id', async ({ params, request }) => {
    const patch = (await request.json()) as { role?: Role; password?: string };
    const user = state.users.find((u) => String(u.id) === String(params.id));
    if (!user) return new HttpResponse(null, { status: 404 });

    // The invariant the backend enforces with a DB trigger and a guard:
    // there must always be at least one admin left.
    if (patch.role && patch.role !== 'admin' && user.role === 'admin'
        && state.users.filter((u) => u.role === 'admin').length <= 1) {
      return HttpResponse.json(
        { message: "That's the only admin left. Make someone else an admin first." },
        { status: 409 },
      );
    }
    if (patch.role) user.role = patch.role;
    return HttpResponse.json(user);
  }),

  http.delete('/api/users/:id', ({ params }) => {
    const id = String(params.id);
    if (id === String(state.currentUserId)) {
      return HttpResponse.json({ message: "You can't remove your own account." }, { status: 409 });
    }
    const user = state.users.find((u) => String(u.id) === id);
    if (user?.role === 'admin' && state.users.filter((u) => u.role === 'admin').length <= 1) {
      return HttpResponse.json(
        { message: "That's the only admin left. Removing it would lock everyone out." },
        { status: 409 },
      );
    }
    state.users = state.users.filter((u) => String(u.id) !== id);
    return noContent();
  }),
];
