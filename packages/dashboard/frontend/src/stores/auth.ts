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

  async function logout(): Promise<void> {
    await AuthApi.logout();
    session.value = { authenticated: false, username: null, passkeyEnrolled: false, tz: null, role: null };
  }

  return { session, loading, error, fetchSession, login, logout };
});
