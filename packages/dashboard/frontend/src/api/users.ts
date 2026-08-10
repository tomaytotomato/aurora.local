// Aurora user management (Phase D iter-10 / D9).
//
// Backs the /users admin-only view. Backend contract:
//   GET    /api/users            → UserSummary[]
//   GET    /api/users/{id}       → UserSummary
//   POST   /api/users            → UserSummary (201)
//   PUT    /api/users/{id}       → UserSummary  (role and/or password)
//   DELETE /api/users/{id}       → 204
//
// Every endpoint requires role=admin — 401 for anonymous, 403 for
// authenticated-but-not-admin. The view gates the sidebar link on
// the session role, so a non-admin never sees these calls fire.

import { http } from './client';

export type Role = 'admin' | 'user' | 'guest';

export interface UserSummary {
  id: number;
  username: string;
  role: Role;
  tz: string;
  createdAt: string;
}

export interface CreateUserRequest {
  username: string;
  password: string;
  role: Role;
  tz?: string | null;
}

export interface UpdateUserRequest {
  role?: Role;
  password?: string;
}

export const UsersApi = {
  async list(): Promise<UserSummary[]> {
    const { data } = await http.get<UserSummary[]>('/users');
    return data;
  },

  async create(req: CreateUserRequest): Promise<UserSummary> {
    // toast: false — the form renders its own inline error copy via
    // humanCopyForError; the global 5xx toast would double-announce.
    const { data } = await http.post<UserSummary>('/users', req, { toast: false });
    return data;
  },

  async update(id: number, req: UpdateUserRequest): Promise<UserSummary> {
    const { data } = await http.put<UserSummary>(`/users/${id}`, req, { toast: false });
    return data;
  },

  async remove(id: number): Promise<void> {
    await http.delete(`/users/${id}`, { toast: false });
  },
};
