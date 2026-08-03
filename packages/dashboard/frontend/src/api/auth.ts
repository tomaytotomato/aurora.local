import { http } from './client';

export interface Session {
  authenticated: boolean;
  username: string | null;
  passkeyEnrolled: boolean;
  tz: string | null;
  /** Phase D role. `admin | user | guest` when authenticated; null when anonymous. */
  role: string | null;
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
  async logout(): Promise<void> {
    await http.post('/auth/logout');
  },
  // Passkey enrollment — v0.2 stub.
  async enrollPasskey(): Promise<void> {
    throw new Error('Passkey enrollment lands in v0.2.');
  },
};
