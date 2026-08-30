import { http } from './client';

export interface Session {
  authenticated: boolean;
  username: string | null;
  passkeyEnrolled: boolean;
  tz: string | null;
  /** Phase D role. `admin | user | guest` when authenticated; null when anonymous. */
  role: string | null;
}

/**
 * Phase D iter-14 (D13). Backend response to POST /auth/logout.
 * When SSO is enabled and a domain is known, {@code next} carries the
 * Authelia logout URL so the SPA bounces through it to clear the
 * shared .{DOMAIN} session cookie. Null when SSO isn't active — the
 * SPA does its usual local redirect to /login.
 */
export interface LogoutResponse {
  next: string | null;
}

export const AuthApi = {
  async session(): Promise<Session> {
    const { data } = await http.get<Session>('/auth/session');
    return data;
  },
  async login(username: string, password: string): Promise<Session> {
    const { data } = await http.post<Session>('/auth/login', { username, password });
    return data;
  },
  /**
   * Spend a recovery code: set a new password, receive the replacement code.
   * Public endpoint — the caller is by definition someone who cannot sign in.
   */
  async recover(username: string, code: string, newPassword: string): Promise<string> {
    const { data } = await http.post<{ ok: boolean; recoveryCode: string }>(
      '/auth/recover', { username, code, newPassword });
    return data.recoveryCode;
  },

  async recoveryStatus(): Promise<{ issued: boolean; issuedAt: string | null }> {
    const { data } = await http.get<{ issued: boolean; issuedAt: string | null }>(
      '/auth/recovery-status');
    return data;
  },

  async logout(): Promise<LogoutResponse> {
    const { data } = await http.post<LogoutResponse>('/auth/logout');
    return data;
  },
  /**
   * Self-service password change. Verifies the current password on the
   * backend before writing the new hash; returns nothing on success
   * so no plaintext ever lives client-side after this call.
   *
   * <p>Errors bubble as axios rejections — the caller unwraps 401
   * (wrong current password), 400 (new one is short, matches the
   * current one, or fails the users-service validator).
   */
  async changePassword(currentPassword: string, newPassword: string): Promise<void> {
    await http.post('/auth/password', { currentPassword, newPassword },
      { toast: false }); // form renders inline error copy; global toast would duplicate
  },
  // Passkey enrollment — v0.2 stub.
  async enrollPasskey(): Promise<void> {
    throw new Error('Passkey enrollment lands in v0.2.');
  },
};
