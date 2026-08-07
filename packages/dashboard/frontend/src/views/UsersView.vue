<script setup lang="ts">
// Admin user management. Small, opinionated: three roles, no permission
// matrix. You can't demote or remove yourself (the mock backend guards
// it too, but the UI shouldn't offer the action in the first place).
import { computed, onMounted, ref } from 'vue';
import { useAuthStore } from '@/stores/auth';
import {
  ROLE_BLURB,
  ROLE_LABELS,
  UsersApi,
  type NewUser,
  type User,
  type UserRole,
} from '@/api/users';
import { humanCopyForError } from '@/lib/http-error-copy';
import { toast } from '@/composables/useToast';
import Card from '@/components/ui/Card.vue';
import Button from '@/components/ui/Button.vue';
import {
  Alert,
  AlertDescription,
  Badge,
  Dialog,
  Input,
  Label,
  Select,
  Skeleton,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui';

const auth = useAuthStore();

const users = ref<User[]>([]);
const loading = ref(false);
const err = ref<string | null>(null);

const roleOptions = (['admin', 'operator', 'viewer'] as UserRole[]).map((r) => ({ value: r, label: ROLE_LABELS[r] }));

const currentUsername = computed(() => auth.session?.username ?? null);
function isSelf(u: User): boolean {
  return !!currentUsername.value && u.username === currentUsername.value;
}

async function load(): Promise<void> {
  loading.value = true;
  err.value = null;
  try {
    users.value = await UsersApi.list();
  } catch (e) {
    err.value = humanCopyForError(e, { subject: 'the user list', action: 'load' });
  } finally {
    loading.value = false;
  }
}
onMounted(load);

async function changeRole(u: User, role: UserRole): Promise<void> {
  if (role === u.role) return;
  const prev = u.role;
  u.role = role; // optimistic
  try {
    await UsersApi.setRole(u.id, role);
    toast({ title: 'Role updated', description: `${u.username} is now ${ROLE_LABELS[role]}.`, variant: 'success', duration: 3000 });
  } catch (e) {
    u.role = prev;
    toast({ title: "Couldn't change role", description: humanCopyForError(e, { subject: 'the role', action: 'change' }), variant: 'destructive' });
  }
}

// remove
const removeTarget = ref<User | null>(null);
const removing = ref(false);
async function confirmRemove(): Promise<void> {
  if (!removeTarget.value) return;
  removing.value = true;
  try {
    await UsersApi.remove(removeTarget.value.id);
    await load();
  } catch (e) {
    toast({ title: "Couldn't remove user", description: humanCopyForError(e, { subject: 'the user', action: 'remove' }), variant: 'destructive' });
  } finally {
    removing.value = false;
    removeTarget.value = null;
  }
}

// add
const addOpen = ref(false);
const draft = ref<NewUser>({ username: '', role: 'operator', password: '' });
const adding = ref(false);
const addErr = ref<string | null>(null);
function openAdd(): void {
  draft.value = { username: '', role: 'operator', password: '' };
  addErr.value = null;
  addOpen.value = true;
}
const canSubmit = computed(() => draft.value.username.trim().length > 0 && draft.value.password.length >= 8);
async function submitAdd(): Promise<void> {
  adding.value = true;
  addErr.value = null;
  try {
    await UsersApi.create({ ...draft.value, username: draft.value.username.trim() });
    addOpen.value = false;
    await load();
    toast({ title: 'User added', description: `${draft.value.username.trim()} can now sign in.`, variant: 'success', duration: 3000 });
  } catch (e) {
    addErr.value = humanCopyForError(e, { subject: 'the user', action: 'add', badRequest: 'That username is already taken.' });
  } finally {
    adding.value = false;
  }
}

function fmt(iso: string | null): string {
  if (!iso) return 'never';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString(undefined, { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}
</script>

<template>
  <section data-view="users">
    <div class="mb-10 on-photo flex items-start justify-between gap-6">
      <div>
        <div class="eyebrow mb-2">Access</div>
        <h1 class="mb-3">Users</h1>
        <p class="max-w-2xl">
          Who can sign in to Aurora and how much they can do. Three roles, no fiddly
          permission matrix: an admin runs the box, an operator works the apps, a viewer
          just looks.
        </p>
      </div>
      <Button size="sm" data-test="users-add" @click="openAdd">Add user</Button>
    </div>

    <Card v-if="err" class="p-6 mb-4">
      <Alert variant="destructive"><AlertDescription>{{ err }}</AlertDescription></Alert>
    </Card>

    <!--
      The role legend used to be a bare <p> below this Card. Over the
      app-wide aurora photo that meant text-foreground (near-black in
      light mode) sat directly on the photo instead of a solid surface
      — legible in dark mode by accident, illegible in light mode. It's
      folded into the same Card now, as a footer row with its own
      border-t, so it always renders on bg-card/text-card-foreground
      regardless of theme. One role per line reads better than a single
      dense sentence with interpunct separators, too.
    -->
    <Card class="p-0 overflow-hidden">
      <Table data-test="users-list">
        <TableHeader>
          <TableRow class="hover:bg-transparent">
            <TableHead>User</TableHead>
            <TableHead class="w-44">Role</TableHead>
            <TableHead class="w-48">Last sign-in</TableHead>
            <TableHead class="w-24">Passkey</TableHead>
            <TableHead class="w-24"></TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          <template v-if="loading && !users.length">
            <TableRow v-for="n in 3" :key="`skeleton-${n}`" class="hover:bg-transparent">
              <TableCell class="align-middle"><Skeleton class="h-4 w-40" /></TableCell>
              <TableCell class="align-middle"><Skeleton class="h-8 w-36" /></TableCell>
              <TableCell class="align-middle"><Skeleton class="h-4 w-32" /></TableCell>
              <TableCell class="align-middle"><Skeleton class="h-5 w-16" /></TableCell>
              <TableCell></TableCell>
            </TableRow>
          </template>
          <TableRow v-else-if="!users.length" class="hover:bg-transparent">
            <TableCell :colspan="5" class="text-center text-sm text-muted-foreground py-10">
              No users yet.
            </TableCell>
          </TableRow>
          <template v-else>
            <TableRow v-for="u in users" :key="u.id" :data-user="u.username">
            <TableCell class="align-middle">
              <span class="font-mono text-foreground">{{ u.username }}</span>
              <Badge v-if="isSelf(u)" tone="info" class="ml-2">you</Badge>
              <div class="text-xs text-muted-foreground mt-0.5">added {{ fmt(u.createdAt) }}</div>
            </TableCell>
            <TableCell class="align-middle">
              <Select
                :model-value="u.role"
                :options="roleOptions"
                :disabled="isSelf(u)"
                class="h-8 w-36 text-sm"
                :aria-label="`Role for ${u.username}`"
                @update:model-value="changeRole(u, $event as UserRole)"
              />
            </TableCell>
            <TableCell class="align-middle text-sm text-muted-foreground">{{ fmt(u.lastLoginAt) }}</TableCell>
            <TableCell class="align-middle">
              <Badge :tone="u.passkeyEnrolled ? 'ok' : 'neutral'">{{ u.passkeyEnrolled ? 'enrolled' : 'no' }}</Badge>
            </TableCell>
            <TableCell class="align-middle text-right">
              <button
                type="button"
                class="text-sm text-muted-foreground hover:text-destructive disabled:opacity-40 disabled:cursor-not-allowed"
                :disabled="isSelf(u)"
                :title="isSelf(u) ? 'You cannot remove your own account' : 'Remove user'"
                data-test="users-remove"
                @click="removeTarget = u"
              >Remove</button>
            </TableCell>
          </TableRow>
          </template>
        </TableBody>
      </Table>

      <dl class="border-t border-border px-6 py-4 space-y-1.5" data-test="users-role-legend">
        <div class="flex gap-2 text-xs">
          <dt class="text-foreground font-medium shrink-0">Admin</dt>
          <dd class="text-muted-foreground">{{ ROLE_BLURB.admin }}</dd>
        </div>
        <div class="flex gap-2 text-xs">
          <dt class="text-foreground font-medium shrink-0">Operator</dt>
          <dd class="text-muted-foreground">{{ ROLE_BLURB.operator }}</dd>
        </div>
        <div class="flex gap-2 text-xs">
          <dt class="text-foreground font-medium shrink-0">Viewer</dt>
          <dd class="text-muted-foreground">{{ ROLE_BLURB.viewer }}</dd>
        </div>
      </dl>
    </Card>

    <!-- Add user -->
    <Dialog :open="addOpen" @update:open="(v) => (addOpen = v)">
      <template #title>Add a user</template>
      <div class="space-y-4">
        <Alert v-if="addErr" variant="destructive"><AlertDescription>{{ addErr }}</AlertDescription></Alert>
        <div>
          <Label for="new-username">Username</Label>
          <Input id="new-username" v-model="draft.username" placeholder="sam" autocomplete="off" />
        </div>
        <div>
          <Label for="new-password">Temporary password</Label>
          <Input id="new-password" v-model="draft.password" type="password" placeholder="at least 8 characters" autocomplete="new-password" />
        </div>
        <div>
          <Label for="new-role">Role</Label>
          <Select id="new-role" :model-value="draft.role" :options="roleOptions" @update:model-value="draft.role = $event as UserRole" />
          <p class="text-xs text-muted-foreground mt-1">{{ ROLE_BLURB[draft.role] }}</p>
        </div>
      </div>
      <template #footer>
        <Button variant="secondary" @click="addOpen = false">Cancel</Button>
        <Button :disabled="!canSubmit || adding" @click="submitAdd">{{ adding ? 'Adding…' : 'Add user' }}</Button>
      </template>
    </Dialog>

    <!-- Remove confirm -->
    <Dialog :open="!!removeTarget" @update:open="(v) => { if (!v) removeTarget = null; }">
      <template #title>Remove {{ removeTarget?.username }}?</template>
      <template #description>They'll lose access immediately. This can't be undone, but you can add them again later.</template>
      <template #footer>
        <Button variant="secondary" @click="removeTarget = null">Cancel</Button>
        <Button variant="danger" :disabled="removing" @click="confirmRemove">{{ removing ? 'Removing…' : 'Remove' }}</Button>
      </template>
    </Dialog>
  </section>
</template>
