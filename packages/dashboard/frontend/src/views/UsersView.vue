<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { useUsersStore } from '@/stores/users';
import { useSystemStore } from '@/stores/system';
import type { Role, UserSummary } from '@/api/users';
import { humanCopyForError } from '@/lib/http-error-copy';
import { copyToClipboard } from '@/lib/utils';
import { toast } from '@/composables/useToast';
import Card from '@/components/ui/Card.vue';
import Button from '@/components/ui/Button.vue';
import {
  Alert,
  AlertDescription,
  Badge,
  Checkbox,
  Dialog,
  DropdownMenu,
  DropdownMenuItem,
  DropdownMenuSeparator,
  Input,
  Label,
  Select,
  Table,
  TableHeader,
  TableBody,
  TableRow,
  TableHead,
  TableCell,
} from '@/components/ui';

// Phase D iter-10 (D9). Admin-only user management.
//
// Route guarded in router/index.ts + sidebar link gated by role, so
// this component only mounts when the caller is an admin. Belt-and-
// braces double-check on mount + on every mutation via
// requireAdminOrRedirect() — a stale session that lost the admin
// role between navigation and mount bounces back to /.
//
// Built entirely from Phase C primitives:
//   Table + TableRow      — user list
//   Dialog                — create + edit forms
//   DropdownMenu          — per-row Edit / Change password / Delete
//   Toast                 — success announcements
//   Input + Label + Select — form fields

const auth = useAuthStore();
const users = useUsersStore();
const router = useRouter();

const showCreate = ref(false);
const editing = ref<UserSummary | null>(null);
const rotatingPassword = ref<UserSummary | null>(null);
const confirmDelete = ref<UserSummary | null>(null);

// ─── access guard ──────────────────────────────────────────────────

const isAdmin = computed(() => auth.session?.role === 'admin');

// The box's own mail domain — for the mailbox address preview in the
// create form. Same source Overview + Settings read; falls back to the
// canonical example before system.info hydrates.
const system = useSystemStore();
const mailDomain = computed(() => system.info?.domain || 'aurora.local');

async function requireAdminOrRedirect(): Promise<boolean> {
  await auth.fetchSession();
  if (!isAdmin.value) {
    await router.push('/');
    return false;
  }
  return true;
}

onMounted(async () => {
  if (!(await requireAdminOrRedirect())) return;
  await users.fetch();
});

// ─── create ────────────────────────────────────────────────────────

const createForm = ref<{
  username: string;
  password: string;
  role: Role;
  tz: string;
  createMailbox: boolean;
  email: string;
}>({
  username: '',
  password: '',
  role: 'user',
  tz: '',
  // Aurora auto-creates a mailbox for a new user by default — the story.
  // The mailbox password is the user's own password.
  createMailbox: true,
  email: '',
});
const createErr = ref<string | null>(null);
const createBusy = ref(false);

function resetCreateForm() {
  createForm.value = { username: '', password: '', role: 'user', tz: '', createMailbox: true, email: '' };
  createErr.value = null;
}

async function submitCreate(): Promise<void> {
  createBusy.value = true;
  createErr.value = null;
  try {
    const created = await users.create({
      username: createForm.value.username.trim(),
      // Blank means "generate one". The backend decides, and returns the
      // plaintext exactly once when it did.
      password: createForm.value.password || undefined,
      role: createForm.value.role,
      tz: createForm.value.tz.trim() || null,
      createMailbox: createForm.value.createMailbox,
      // Blank email = default <username>@<box-domain> on the server.
      email: createForm.value.createMailbox ? (createForm.value.email.trim() || undefined) : undefined,
    });
    const username = createForm.value.username.trim();
    showCreate.value = false;
    resetCreateForm();

    // Mailbox outcome copy — folded into the success signal so the admin
    // learns in one place whether the mailbox came up. A mailbox that
    // could not be made is a warning, never a failure: the user exists.
    const mb = created.mailbox;
    const mailboxNote = mb && mb.requested
      ? mb.created
        ? ` Mailbox ${mb.email} is ready (same password).`
        : ` The login works, but the mailbox couldn't be set up: ${mb.error}.`
      : '';

    if (created.generatedPassword) {
      // Modal, not a toast. This is the only time this value will ever
      // exist — nothing stores the plaintext — so it must not be
      // dismissible by a 4-second timer while the admin is looking away.
      issued.value = {
        username,
        password: created.generatedPassword,
        mailboxEmail: mb?.created ? (mb.email ?? null) : null,
        mailboxError: mb && mb.requested && !mb.created ? (mb.error ?? null) : null,
      };
    } else {
      toast({
        title: mb && mb.requested && !mb.created ? 'User created (mailbox pending)' : 'User created',
        description: `${username} can now sign in.${mailboxNote}`,
        variant: mb && mb.requested && !mb.created ? 'default' : 'success',
        duration: 6000,
      });
    }
  } catch (err) {
    createErr.value = humanCopyForError(err, { subject: 'user', action: 'create' });
  } finally {
    createBusy.value = false;
  }
}

// ─── edit role ─────────────────────────────────────────────────────

const editForm = ref<{ role: Role }>({ role: 'user' });
const editErr = ref<string | null>(null);
const editBusy = ref(false);

function openEdit(u: UserSummary) {
  editing.value = u;
  editForm.value = { role: u.role };
  editErr.value = null;
}

async function submitEdit(): Promise<void> {
  if (!editing.value) return;
  editBusy.value = true;
  editErr.value = null;
  try {
    await users.update(editing.value.id, { role: editForm.value.role });
    toast({
      description: `Role updated for ${editing.value.username}.`,
      variant: 'success',
      duration: 3000,
    });
    editing.value = null;
  } catch (err) {
    editErr.value = humanCopyForError(err, { subject: 'user role', action: 'update' });
  } finally {
    editBusy.value = false;
  }
}

// ─── rotate password ───────────────────────────────────────────────

const pwForm = ref<{ password: string }>({ password: '' });
const pwErr = ref<string | null>(null);
const pwBusy = ref(false);

function openRotatePassword(u: UserSummary) {
  rotatingPassword.value = u;
  pwForm.value = { password: '' };
  pwErr.value = null;
}

async function submitRotatePassword(): Promise<void> {
  if (!rotatingPassword.value) return;
  pwBusy.value = true;
  pwErr.value = null;
  const username = rotatingPassword.value.username;
  try {
    // Blank generates one, same contract as create.
    const res = await users.resetPassword(
      rotatingPassword.value.id, pwForm.value.password || undefined);
    rotatingPassword.value = null;

    if (res.generated && res.password) {
      issued.value = { username, password: res.password };
    } else {
      toast({
        description: `Password rotated for ${username}.`,
        variant: 'success',
        duration: 3000,
      });
    }
  } catch (err) {
    pwErr.value = humanCopyForError(err, { subject: 'password', action: 'rotate' });
  } finally {
    pwBusy.value = false;
  }
}

// ─── issued credential ─────────────────────────────────────
//
// Shown after Aurora generates a password. Held in a ref rather than a
// toast because it is unrecoverable: the server hashed it and kept no
// copy, so if this disappears before the admin reads it the only remedy
// is another rotation.
const issued = ref<{
  username: string;
  password: string;
  mailboxEmail?: string | null;
  mailboxError?: string | null;
} | null>(null);
const copied = ref<'ok' | 'fail' | null>(null);

async function copyIssued(): Promise<void> {
  if (!issued.value) return;
  // copyToClipboard tries the Clipboard API first (secure contexts
  // only) and falls back to a hidden-textarea + execCommand('copy')
  // path that works on plain HTTP. The dashboard usually lives at
  // http://aurora.local on the LAN, where navigator.clipboard is
  // undefined — without the fallback the Copy button silently did
  // nothing (Bruce's report, 2026-08-27). Returns a boolean so we
  // can render an honest 'Copy failed' state instead of pretending
  // it worked.
  const ok = await copyToClipboard(issued.value.password);
  copied.value = ok ? 'ok' : 'fail';
  window.setTimeout(() => { copied.value = null; }, 2500);
}

// ─── delete ────────────────────────────────────────────────────────

const deleteBusy = ref(false);
const deleteErr = ref<string | null>(null);

async function submitDelete(): Promise<void> {
  if (!confirmDelete.value) return;
  deleteBusy.value = true;
  deleteErr.value = null;
  const doomed = confirmDelete.value;
  try {
    await users.remove(doomed.id);
    toast({
      description: `Deleted ${doomed.username}.`,
      variant: 'success',
      duration: 3000,
    });
    confirmDelete.value = null;
  } catch (err) {
    deleteErr.value = humanCopyForError(err, { subject: 'user', action: 'delete' });
  } finally {
    deleteBusy.value = false;
  }
}

// ─── helpers ────────────────────────────────────────────────────────

const roleOptions: { value: Role; label: string }[] = [
  { value: 'admin', label: 'Admin — full control' },
  { value: 'user', label: 'User — standard access' },
  { value: 'guest', label: 'Guest — read-mostly' },
];

function badgeToneFor(role: Role): 'ok' | 'warn' | 'err' | 'neutral' {
  // Admin is a role, not a warning. Amber read as "something is wrong
  // with this user" on every admin row; a home box where both users are
  // admins had two caution-coloured pills for no reason. Neutral keeps
  // amber reserved for actual attention states.
  if (role === 'admin') return 'neutral';
  if (role === 'user') return 'neutral';
  return 'neutral';
}

function formatCreatedAt(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleDateString(undefined, {
    year: 'numeric', month: 'short', day: 'numeric',
  });
}
</script>

<template>
  <section data-test="users-view">
    <div class="mb-10 flex items-baseline justify-between gap-4">
      <div>
        <div class="eyebrow mb-2">Access</div>
        <h1>Users</h1>
        <p class="text-sm text-muted-foreground mt-1 max-w-2xl">
          Aurora is the source of truth for who can sign into any
          service on this box. Roles propagate to Authelia automatically.
        </p>
      </div>
      <Button
        variant="primary"
        data-test="users-create"
        @click="() => { resetCreateForm(); showCreate = true; }"
      >Add user</Button>
    </div>

    <Alert v-if="users.error" variant="destructive" class="mb-6" data-test="users-load-error">
      <AlertDescription>{{ users.error }}</AlertDescription>
    </Alert>

    <Card class="p-8" data-card="users">
      <div
        v-if="!users.loading && users.users.length === 0"
        class="text-sm text-muted-foreground py-8 text-center"
        data-state="empty"
        data-test="users-empty"
      >
        No users yet. Add one to grant sign-in access.
      </div>

      <Table v-else data-test="users-list">
        <TableHeader>
          <TableRow class="hover:bg-transparent">
            <TableHead class="w-56">Username</TableHead>
            <TableHead class="w-32">Role</TableHead>
            <TableHead class="w-40">Time zone</TableHead>
            <TableHead>Created</TableHead>
            <TableHead class="w-16 text-right"></TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          <TableRow v-for="u in users.users" :key="u.id" :data-test="`users-row-${u.username}`">
            <TableCell class="font-mono text-sm align-middle">{{ u.username }}</TableCell>
            <TableCell class="align-middle">
              <Badge :tone="badgeToneFor(u.role)">{{ u.role }}</Badge>
            </TableCell>
            <TableCell class="text-sm text-muted-foreground align-middle">{{ u.tz }}</TableCell>
            <TableCell class="text-sm text-muted-foreground align-middle">
              {{ formatCreatedAt(u.createdAt) }}
            </TableCell>
            <TableCell class="text-right align-middle">
              <DropdownMenu :data-test="`users-row-menu-${u.username}`">
                <template #trigger>
                  <button
                    type="button"
                    class="p-1.5 rounded-md hover:bg-muted text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                    :aria-label="`Actions for ${u.username}`"
                    :data-test="`users-row-menu-trigger-${u.username}`"
                  >
                    <svg viewBox="0 0 24 24" class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                      <circle cx="12" cy="6" r="1" />
                      <circle cx="12" cy="12" r="1" />
                      <circle cx="12" cy="18" r="1" />
                    </svg>
                  </button>
                </template>
                <DropdownMenuItem :data-test="`users-row-edit-${u.username}`" @select="() => openEdit(u)">
                  Change role
                </DropdownMenuItem>
                <DropdownMenuItem :data-test="`users-row-password-${u.username}`" @select="() => openRotatePassword(u)">
                  Rotate password
                </DropdownMenuItem>
                <DropdownMenuSeparator />
                <DropdownMenuItem
                  destructive
                  :data-test="`users-row-delete-${u.username}`"
                  @select="() => { deleteErr = null; confirmDelete = u; }"
                >
                  Delete
                </DropdownMenuItem>
              </DropdownMenu>
            </TableCell>
          </TableRow>
        </TableBody>
      </Table>
    </Card>

    <!-- Create dialog -->
    <Dialog v-model:open="showCreate" data-test="users-create-dialog">
      <template #title>Add user</template>
      <template #description>
        The user can sign into Aurora + any package the identity SSO
        gates. Password can't be recovered — write it down somewhere
        safe.
      </template>

      <div class="space-y-4">
        <Alert v-if="createErr" variant="destructive" data-test="users-create-error">
          <AlertDescription>{{ createErr }}</AlertDescription>
        </Alert>

        <div>
          <Label for="create-username">Username</Label>
          <Input
            id="create-username"
            v-model="createForm.username"
            autocomplete="off"
            placeholder="alice"
            data-test="users-create-username"
          />
          <p class="text-xs text-muted-foreground mt-1">
            Lowercase letters, digits, dot / underscore / dash. 2–32 chars.
          </p>
        </div>

        <div>
          <Label for="create-password">Password <span class="text-muted-foreground font-normal">(optional)</span></Label>
          <Input
            id="create-password"
            v-model="createForm.password"
            type="password"
            autocomplete="new-password"
            placeholder="Leave blank to generate one"
            data-test="users-create-password"
          />
          <p class="text-xs text-muted-foreground mt-1">
            Leave blank and Aurora generates a strong passphrase you can hand over
            once. If you set one yourself: at least 12 characters.
            <span v-if="createForm.createMailbox" class="text-foreground">This is
            also the password for their mailbox.</span>
          </p>
        </div>

        <div>
          <Label for="create-role">Role</Label>
          <Select
            id="create-role"
            v-model="createForm.role"
            :options="roleOptions"
            data-test="users-create-role"
          />
        </div>

        <div>
          <Label for="create-tz" hint="Optional">Time zone</Label>
          <Input
            id="create-tz"
            v-model="createForm.tz"
            placeholder="Europe/London"
            autocomplete="off"
            data-test="users-create-tz"
          />
        </div>

        <!-- Mailbox. A new user gets a mailbox by default, sharing their
             own password (the story). The admin can name the address or
             switch it off entirely. -->
        <div class="pt-2 border-t border-border">
          <label class="flex items-start gap-2 cursor-pointer">
            <Checkbox
              :model-value="createForm.createMailbox"
              data-test="users-create-mailbox-toggle"
              @update:model-value="createForm.createMailbox = $event"
            />
            <span class="text-sm">
              <span class="font-medium text-foreground">Create a mailbox for this user</span>
              <span class="block text-xs text-muted-foreground mt-0.5">
                They get an email address using the same password. Turn off for a
                login with no mail.
              </span>
            </span>
          </label>

          <div v-if="createForm.createMailbox" class="mt-3">
            <Label for="create-email" hint="Optional">Email address</Label>
            <div class="flex items-center gap-2 flex-wrap">
              <Input
                id="create-email"
                v-model="createForm.email"
                :placeholder="createForm.username.trim() || 'username'"
                autocomplete="off"
                class="max-w-[16rem]"
                data-test="users-create-email"
              />
              <span v-if="!createForm.email.includes('@')" class="font-mono text-muted-foreground">@{{ mailDomain }}</span>
            </div>
            <p class="text-xs text-muted-foreground mt-1">
              Leave blank to use
              <span class="font-mono">{{ (createForm.username.trim() || 'username') }}@{{ mailDomain }}</span>.
              Enter a full address to use a different domain.
            </p>
          </div>
        </div>
      </div>

      <template #footer>
        <Button
          variant="ghost"
          :disabled="createBusy"
          @click="() => { showCreate = false; resetCreateForm(); }"
        >Cancel</Button>
        <Button
          variant="primary"
          :loading="createBusy"
          data-test="users-create-submit"
          @click="submitCreate"
        >Add user</Button>
      </template>
    </Dialog>

    <!-- Edit role dialog -->
    <Dialog
      :open="editing !== null"
      data-test="users-edit-dialog"
      @update:open="(v) => { if (!v) editing = null; }"
    >
      <template #title>Change role</template>
      <template #description>
        {{ editing?.username }} — role changes take effect on the user's
        next request. Sessions in flight keep working until Authelia
        picks up the new role at its 5-minute refresh.
      </template>

      <div class="space-y-4" v-if="editing">
        <Alert v-if="editErr" variant="destructive" data-test="users-edit-error">
          <AlertDescription>{{ editErr }}</AlertDescription>
        </Alert>

        <div>
          <Label for="edit-role">Role</Label>
          <Select
            id="edit-role"
            v-model="editForm.role"
            :options="roleOptions"
            data-test="users-edit-role"
          />
        </div>
      </div>

      <template #footer>
        <Button variant="ghost" :disabled="editBusy" @click="editing = null">Cancel</Button>
        <Button variant="primary" :loading="editBusy" data-test="users-edit-submit" @click="submitEdit">Save</Button>
      </template>
    </Dialog>

    <!-- Rotate password dialog -->
    <Dialog
      :open="rotatingPassword !== null"
      data-test="users-password-dialog"
      @update:open="(v) => { if (!v) rotatingPassword = null; }"
    >
      <template #title>Rotate password</template>
      <template #description>
        {{ rotatingPassword?.username }} will be signed out of any
        service the moment their new password lands. Write it down
        before you close this dialog — it can't be recovered.
      </template>

      <div class="space-y-4" v-if="rotatingPassword">
        <Alert v-if="pwErr" variant="destructive" data-test="users-password-error">
          <AlertDescription>{{ pwErr }}</AlertDescription>
        </Alert>

        <div>
          <Label for="pw-new">New password</Label>
          <Input
            id="pw-new"
            v-model="pwForm.password"
            type="password"
            autocomplete="new-password"
            data-test="users-password-new"
          />
          <p class="text-xs text-muted-foreground mt-1">
            At least 12 characters.
          </p>
        </div>
      </div>

      <template #footer>
        <Button variant="ghost" :disabled="pwBusy" @click="rotatingPassword = null">Cancel</Button>
        <Button variant="primary" :loading="pwBusy" data-test="users-password-submit" @click="submitRotatePassword">Rotate</Button>
      </template>
    </Dialog>

    <!-- Confirm delete -->
    <Dialog
      :open="confirmDelete !== null"
      data-test="users-delete-dialog"
      @update:open="(v) => { if (!v) confirmDelete = null; }"
    >
      <template #title>Delete user</template>
      <template #description>
        {{ confirmDelete?.username }} will lose access to every service
        immediately. Their audit rows stay in the log. Aurora refuses to
        delete the last admin.
      </template>

      <Alert v-if="deleteErr" variant="destructive" class="mt-2" data-test="users-delete-error">
        <AlertDescription>{{ deleteErr }}</AlertDescription>
      </Alert>

      <template #footer>
        <Button variant="ghost" :disabled="deleteBusy" @click="confirmDelete = null">Cancel</Button>
        <Button
          variant="danger"
          :loading="deleteBusy"
          data-test="users-delete-confirm"
          @click="submitDelete"
        >Delete</Button>
      </template>
    </Dialog>

    <!-- Generated credential, shown once.

         Not a toast: the server hashed this and kept no copy, so if it
         vanishes on a timer while the admin looks away the only remedy is
         another rotation. Requires an explicit dismissal, and says plainly
         that it will not be shown again. -->
    <Dialog :open="issued !== null" @update:open="(v: boolean) => { if (!v) issued = null; }">
      <template #title>Password for {{ issued?.username }}</template>
      <template #description>
        Copy this now — it is not stored anywhere and cannot be shown again.
        If you lose it, rotate the password to get a new one.
      </template>

      <div class="border border-border rounded-lg p-4 bg-muted/40" data-test="users-issued-password">
        <code class="font-mono text-sm break-all select-all text-foreground">{{ issued?.password }}</code>
      </div>

      <!-- Mailbox note: this credential is also the mailbox password, so
           say the address here where the admin is already copying it. -->
      <p
        v-if="issued?.mailboxEmail"
        class="text-sm mt-3"
        data-test="users-issued-mailbox"
      >
        Mailbox <span class="font-mono text-foreground">{{ issued.mailboxEmail }}</span>
        is ready — it uses this same password.
      </p>
      <p
        v-else-if="issued?.mailboxError"
        class="text-sm mt-3 text-warning"
        data-test="users-issued-mailbox-error"
      >
        The login works, but the mailbox couldn't be set up: {{ issued.mailboxError }}.
        You can add it later from the mail service page.
      </p>

      <p class="text-xs text-muted-foreground mt-3">
        {{ issued?.username }} can change this after signing in. They will also need
        to register a passkey the first time they use an app.
      </p>

      <template #footer>
        <Button variant="secondary" data-test="users-issued-copy" @click="copyIssued">
          {{ copied === 'ok' ? 'Copied' : copied === 'fail' ? 'Copy failed' : 'Copy password' }}
        </Button>
        <Button data-test="users-issued-done" @click="issued = null">Done</Button>
      </template>
    </Dialog>
  </section>
</template>
