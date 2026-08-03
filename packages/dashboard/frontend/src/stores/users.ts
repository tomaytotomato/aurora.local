import { defineStore } from 'pinia';
import { ref } from 'vue';
import { UsersApi, type UserSummary, type CreateUserRequest, type UpdateUserRequest } from '@/api/users';
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

  async function create(req: CreateUserRequest): Promise<UserSummary> {
    const created = await UsersApi.create(req);
    // Optimistic: refetch to pick up the new row + any server-side
    // decoration (createdAt timestamp). Cheap for a homelab user set.
    await fetch();
    return created;
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

  return { users, loading, error, fetch, create, update, remove };
});
