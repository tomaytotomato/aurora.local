// User fixtures. The first row is the bootstrap admin and matches the
// mock session's username, so the Users view can mark "you" and refuse
// to let you demote or remove yourself.
//
// Roles are Phase D's vocabulary — admin | user | guest — and the ids are
// numbers because that is what the backend hands out.

import type { UserSummary } from '@/api/users';

export const CURRENT_USER_ID = 1;

export function initialUsers(): UserSummary[] {
  return [
    {
      id: CURRENT_USER_ID,
      username: 'admin',
      role: 'admin',
      tz: 'Europe/London',
      createdAt: '2026-07-31T09:12:00Z',
    },
    {
      id: 2,
      username: 'sam',
      role: 'user',
      tz: 'Europe/London',
      createdAt: '2026-08-02T14:03:00Z',
    },
    {
      id: 3,
      username: 'guest',
      role: 'guest',
      tz: 'UTC',
      createdAt: '2026-08-04T11:20:00Z',
    },
  ];
}
