import { defineStore } from 'pinia';
import { ref } from 'vue';
import { AuthApi, type Session } from '@/api/auth';

export const useAuthStore = defineStore('auth', () => {
  const session = ref<Session | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function fetchSession(): Promise<Session | null> {
    loading.value = true;
    error.value = null;
    try {
      session.value = await AuthApi.session();
      return session.value;
    } catch (e) {
      // 401 is normal — surface as unauthenticated, not as error.
      session.value = { authenticated: false, username: null, passkeyEnrolled: false, tz: null, role: null };
      return session.value;
    } finally {
      loading.value = false;
    }
  }

  async function login(username: string, password: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      session.value = await AuthApi.login(username, password);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Login failed';
      throw e;
    } finally {
      loading.value = false;
    }
  }

  /**
   * Phase D iter-14 (D13). Log out of Aurora and, when SSO is on,
   * return the Authelia logout URL so callers can bounce through it
   * to clear the shared .{DOMAIN} session cookie.
   *
   * <p>Callers are expected to call `window.location.href = next` if
   * a next URL is returned. Handing the redirect to the caller (not
   * doing it here) keeps this store test-friendly — mocking
   * {@code window.location} inside a store is fiddly.
   */
  async function logout(): Promise<string | null> {
    const { next } = await AuthApi.logout();
    session.value = { authenticated: false, username: null, passkeyEnrolled: false, tz: null, role: null };
    return next ?? null;
  }

  return { session, loading, error, fetchSession, login, logout };
});
