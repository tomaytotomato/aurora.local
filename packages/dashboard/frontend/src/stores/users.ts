import { defineStore } from 'pinia';
import { ref } from 'vue';
import { UsersApi, type UserSummary, type CreateUserRequest, type UpdateUserRequest, type CreatedUser, type GeneratedPassword } from '@/api/users';
import { humanCopyForError } from '@/lib/http-error-copy';

/**
 * Aurora user management store (Phase D iter-10 / D9).
 *
 * <p>Backs the /users admin-only view. Kept as a Pinia store so a
 * follow-up (a sidebar badge showing "N users pending review",
 * cross-view session termination, …) can share the state without
 * re-fetching.
 */
export const useUsersStore = defineStore('users', () => {
  const users = ref<UserSummary[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function fetch(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      users.value = await UsersApi.list();
    } catch (err) {
      error.value = humanCopyForError(err, { subject: 'users', action: 'load' });
    } finally {
      loading.value = false;
    }
  }

  async function create(req: CreateUserRequest): Promise<CreatedUser> {
    const created = await UsersApi.create(req);
    // Optimistic: refetch to pick up the new row + any server-side
    // decoration (createdAt timestamp). Cheap for a homelab user set.
    await fetch();
    return created;
  }

  /**
   * Reset a password, returning the generated one when Aurora chose it.
   *
   * No refetch: nothing user-visible on the row changes, and the caller
   * needs the response synchronously to show the secret once.
   */
  async function resetPassword(id: number, password?: string): Promise<GeneratedPassword> {
    return UsersApi.resetPassword(id, password);
  }

  async function update(id: number, req: UpdateUserRequest): Promise<UserSummary> {
    const updated = await UsersApi.update(id, req);
    await fetch();
    return updated;
  }

  async function remove(id: number): Promise<void> {
    await UsersApi.remove(id);
    await fetch();
  }

  return { users, loading, error, fetch, create, update, remove, resetPassword };
});
