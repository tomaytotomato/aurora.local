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
  async logout(): Promise<LogoutResponse> {
    const { data } = await http.post<LogoutResponse>('/auth/logout');
    return data;
  },
  // Passkey enrollment — v0.2 stub.
  async enrollPasskey(): Promise<void> {
    throw new Error('Passkey enrollment lands in v0.2.');
  },
};
