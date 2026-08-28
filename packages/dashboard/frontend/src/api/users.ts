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
  /**
   * Optional. Omit (or send blank) and Aurora generates a strong
   * passphrase, returned once as `CreatedUser.generatedPassword`.
   */
  password?: string;
  role: Role;
  tz?: string | null;
  /**
   * Mailbox address for the auto-created mailbox. A bare local part
   * (mailbox on the box domain) or a full `local@domain`. Omit to use the
   * default `<username>@<box-domain>`. The mailbox password is the user's
   * own password.
   */
  email?: string;
  /**
   * Whether to auto-create a mailbox for the new user (default true on the
   * server). Send false to create a login with no mailbox.
   */
  createMailbox?: boolean;
}

export interface UpdateUserRequest {
  role?: Role;
  /** Password is optional — omit it and Aurora generates a strong one. */
  password?: string;
}

/**
 * Result of creating a user.
 *
 * `generatedPassword` is non-null only when Aurora chose the password,
 * i.e. the caller left it blank. It is returned exactly once and stored
 * nowhere — the plaintext does not exist server-side after this response,
 * so a lost value means a reset, not a lookup. Show it, let the admin copy
 * it, and do not persist it client-side either.
 */
/**
 * What happened to the new user's auto-provisioned mailbox. The mailbox
 * shares the user's own password. Best-effort on the server: a mail
 * failure never fails user creation, so `created` can be false with the
 * user still made — `error` says why.
 */
export interface MailboxOutcome {
  /** Whether a mailbox was attempted (false only when the admin opted out). */
  requested: boolean;
  /** The address Aurora tried to create; null when not requested. */
  email: string | null;
  /** True when the mailbox now exists and works. */
  created: boolean;
  /** A human reason when requested but not created; null on success. */
  error: string | null;
}

export interface CreatedUser {
  user: UserSummary;
  generatedPassword: string | null;
  /** Outcome of the auto-mailbox provisioning. Absent on older servers. */
  mailbox?: MailboxOutcome;
}

export interface GeneratedPassword {
  password: string | null;
  generated: boolean;
}

export const UsersApi = {
  async list(): Promise<UserSummary[]> {
    const { data } = await http.get<UserSummary[]>('/users');
    return data;
  },

  async create(req: CreateUserRequest): Promise<CreatedUser> {
    // toast: false — the form renders its own inline error copy via
    // humanCopyForError; the global 5xx toast would double-announce.
    const { data } = await http.post<CreatedUser>('/users', req, { toast: false });
    return data;
  },

  /**
   * Reset a password. Omit `password` to have Aurora generate one, which
   * is the intended path: an admin-chosen password is known to the admin
   * and tends to travel by chat and never get changed.
   */
  async resetPassword(id: number, password?: string): Promise<GeneratedPassword> {
    const { data } = await http.post<GeneratedPassword>(
      `/users/${id}/password`, password ? { password } : {}, { toast: false });
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
