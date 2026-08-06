// Admin user fixtures. The first row is the bootstrap admin (matches the
// mock session's username) so the Users view can mark "you" and guard
// against demoting or removing yourself.

import type { User } from '@/api/users';

export const CURRENT_USER_ID = 'user-admin';

export function initialUsers(): User[] {
  return [
    {
      id: CURRENT_USER_ID,
      username: 'admin',
      role: 'admin',
      createdAt: '2026-07-31T09:12:00Z',
      lastLoginAt: '2026-08-06T08:12:00Z',
      passkeyEnrolled: false,
    },
    {
      id: 'user-partner',
      username: 'sam',
      role: 'operator',
      createdAt: '2026-08-02T14:03:00Z',
      lastLoginAt: '2026-08-05T19:40:00Z',
      passkeyEnrolled: true,
    },
    {
      id: 'user-guest',
      username: 'guest',
      role: 'viewer',
      createdAt: '2026-08-04T11:20:00Z',
      lastLoginAt: null,
      passkeyEnrolled: false,
    },
  ];
}
