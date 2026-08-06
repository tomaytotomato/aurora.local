// Admin user management (2026-08-06). Aurora is single-admin at first
// boot (the onboarding bootstrap), but a homestack is often shared with
// a partner or housemate, so this manages the small set of people who
// can sign in and how much each can do.
//
// Roles are deliberately few and opinionated — three levels, not a
// permission matrix:
//   admin    — full control, including managing other users
//   operator — start/stop apps, edit config, but not manage users
//   viewer   — read-only dashboards

import { http } from './client';

export type UserRole = 'admin' | 'operator' | 'viewer';

export interface User {
  id: string;
  username: string;
  role: UserRole;
  /** ISO-8601 UTC. */
  createdAt: string;
  /** ISO-8601 UTC; null if they've never signed in. */
  lastLoginAt: string | null;
  passkeyEnrolled: boolean;
}

export interface NewUser {
  username: string;
  role: UserRole;
  password: string;
}

export const ROLE_LABELS: Record<UserRole, string> = {
  admin: 'Admin',
  operator: 'Operator',
  viewer: 'Viewer',
};

export const ROLE_BLURB: Record<UserRole, string> = {
  admin: 'Full control, including managing users.',
  operator: 'Start and stop apps, edit config. Cannot manage users.',
  viewer: 'Read-only. Can look, cannot touch.',
};

export const UsersApi = {
  async list(): Promise<User[]> {
    const { data } = await http.get<User[]>('/users');
    return data;
  },
  async create(body: NewUser): Promise<User> {
    const { data } = await http.post<User>('/users', body);
    return data;
  },
  async setRole(id: string, role: UserRole): Promise<User> {
    const { data } = await http.patch<User>(`/users/${encodeURIComponent(id)}`, { role });
    return data;
  },
  async remove(id: string): Promise<void> {
    await http.delete(`/users/${encodeURIComponent(id)}`);
  },
};
