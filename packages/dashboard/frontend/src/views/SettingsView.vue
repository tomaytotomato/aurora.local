<script setup lang="ts">
import { useAuthStore } from '@/stores/auth';
import { AuthApi } from '@/api/auth';
import { useSystemStore } from '@/stores/system';
import { AuditApi, type AuditEvent } from '@/api/audit';
import { MdnsApi, type MdnsAlias } from '@/api/mdns';
import { humanCopyForError } from '@/lib/http-error-copy';
import { toast } from '@/composables/useToast';
import Card from '@/components/ui/Card.vue';
import Button from '@/components/ui/Button.vue';
import NotificationsCard from '@/components/NotificationsCard.vue';
import ProxyRoutesCard from '@/components/ProxyRoutesCard.vue';
import SettingsPortabilityCard from '@/components/SettingsPortabilityCard.vue';
import {
  Alert,
  AlertDescription,
  Badge,
  Dialog,
  Input,
  Label,
  Skeleton,
  Table,
  TableHeader,
  TableBody,
  TableRow,
  TableHead,
  TableCell,
} from '@/components/ui';
import { useRouter } from 'vue-router';
import { computed, onMounted, ref } from 'vue';

const auth = useAuthStore();
const system = useSystemStore();
const router = useRouter();

const info = computed(() => system.info);

async function signOut(): Promise<void> {
  const next = await auth.logout();
  if (next) {
    // Phase D iter-14 (D13): Authelia logout bounces the browser to
    // the `rd` param after clearing the shared session cookie.
    window.location.href = next;
    return;
  }
  router.push('/login');
}

// ── Change password ─────────────────────────────────────────────
//
// Bug context: the Security page's "Fix it →" links for weak-admin
// findings landed on /settings#account where there was nothing but a
// Sign out button — the operator was told to rotate and given nowhere
// to do it. This dialog is what makes those links useful.
//
// Shape mirrors UsersView's Rotate-password dialog with one extra field:
// the current password. The backend requires it, and a self-service
// flow that skipped it would leave a stolen session cookie enough on
// its own to take the account over.

const showChangePassword = ref(false);
const pwForm = ref({ current: '', next: '', confirm: '' });
const pwBusy = ref(false);
const pwErr = ref<string | null>(null);

function openChangePassword(): void {
  pwForm.value = { current: '', next: '', confirm: '' };
  pwErr.value = null;
  showChangePassword.value = true;
}

async function submitChangePassword(): Promise<void> {
  pwErr.value = null;
  if (pwForm.value.next !== pwForm.value.confirm) {
    pwErr.value = 'The two new-password fields do not match.';
    return;
  }
  if (pwForm.value.next.length < 12) {
    pwErr.value = 'New password must be at least 12 characters.';
    return;
  }
  if (pwForm.value.next === pwForm.value.current) {
    pwErr.value = 'New password matches the current one.';
    return;
  }
  pwBusy.value = true;
  try {
    await AuthApi.changePassword(pwForm.value.current, pwForm.value.next);
    showChangePassword.value = false;
    toast({
      title: 'Password changed',
      description: 'You stay signed in — use the new one next time.',
      variant: 'success',
      duration: 4000,
    });
  } catch (err) {
    const status = (err as { response?: { status?: number } })?.response?.status;
    if (status === 401) {
      // The one 401 that is a domain answer, not a session signal: the
      // current-password field is wrong. Say so. Anything vaguer
      // ("sign in again") pretends to be a session issue and sends
      // the operator hunting for the wrong problem.
      pwErr.value = 'That current password does not match. Try again.';
    } else if (status === 400) {
      pwErr.value = 'That new password was rejected. Pick a different one.';
    } else {
      pwErr.value = humanCopyForError(err, {
        subject: 'your password',
        action: 'change',
      });
    }
  } finally {
    pwBusy.value = false;
  }
}

// iter-31 audit-log viewer. Backend endpoint sorts newest first; the
// card renders the tail of the last 100 events with an optional
// action-prefix filter (typed into a small input). Wall-clock UI
// intentionally minimal — an operator hunting for a specific event
// will curl the endpoint directly, this card is for glanceability.
const auditEvents = ref<AuditEvent[]>([]);
const auditFilter = ref<string>('');
const auditLoading = ref<boolean>(false);
const auditErr = ref<string | null>(null);

async function loadAudit(): Promise<void> {
  auditLoading.value = true;
  auditErr.value = null;
  try {
    auditEvents.value = await AuditApi.list({
      action: auditFilter.value.trim() || undefined,
      limit: 100,
    });
  } catch (e: unknown) {
    auditErr.value = humanCopyForError(e, {
      subject: 'the audit log',
      action: 'see',
      badRequest: 'Filter must be an action prefix like "security." or "onboarding.".',
    });
    auditEvents.value = [];
  } finally {
    auditLoading.value = false;
  }
}

function formatAuditTs(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
}

// LAN discovery (2026-08-03 v0.3.x productionize). Renders the list of
// mDNS aliases the backend publishes for each enabled package's vhosts.
// Manual reconcile is here for the "I just enabled a package, publish
// now" case; the backend also drift-reconciles every 60s so this button
// is a nudge, not a requirement.
const mdnsAliases = ref<MdnsAlias[]>([]);
const mdnsLoading = ref<boolean>(false);
const mdnsErr = ref<string | null>(null);
const mdnsReconciling = ref<boolean>(false);

async function loadMdns(): Promise<void> {
  mdnsLoading.value = true;
  mdnsErr.value = null;
  try {
    const p = await MdnsApi.list();
    mdnsAliases.value = p.aliases;
  } catch (err) {
    mdnsErr.value = humanCopyForError(err, { subject: 'mDNS aliases', action: 'load' });
  } finally {
    mdnsLoading.value = false;
  }
}

async function reconcileMdns(): Promise<void> {
  mdnsReconciling.value = true;
  mdnsErr.value = null;
  try {
    const p = await MdnsApi.reconcile();
    mdnsAliases.value = p.aliases;
    toast({
      title: 'LAN discovery reconciled',
      description: `${p.up}/${p.total} alias${p.total === 1 ? '' : 'es'} up`,
      variant: p.failed > 0 ? 'warning' : 'success',
      duration: 4000,
    });
  } catch (err) {
    mdnsErr.value = humanCopyForError(err, { subject: 'mDNS aliases', action: 'reconcile' });
  } finally {
    mdnsReconciling.value = false;
  }
}

function mdnsToneFor(state: MdnsAlias['state']): 'ok' | 'warn' | 'err' | 'neutral' {
  if (state === 'up') return 'ok';
  if (state === 'failed') return 'err';
  return 'neutral';
}

onMounted(() => { void loadAudit(); void loadMdns(); });
</script>

<template>
  <section>
    <div class="mb-10 on-photo">
      <div class="eyebrow mb-2">Preferences</div>
      <h1>Settings</h1>
    </div>

    <div class="space-y-6 max-w-2xl">
      <Card class="p-8" id="account">
        <h3 class="card-title mb-1">Account</h3>
        <p class="card-subtitle mb-4">
          Signed in as <span class="font-mono text-foreground">{{ auth.session?.username ?? '—' }}</span>.
        </p>
        <div class="flex flex-wrap gap-2">
          <Button
            variant="secondary"
            size="sm"
            data-test="settings-change-password"
            @click="openChangePassword"
          >Change password</Button>
          <Button variant="secondary" size="sm" @click="signOut">Sign out</Button>
        </div>
      </Card>

      <Card class="p-8">
        <h3 class="card-title mb-1">Second factor</h3>
        <p class="card-subtitle mb-3">Passkey</p>
        <Alert variant="info">
          <AlertDescription>
            Passkey sign-in isn't wired up on this box yet. Second
            factors for every gated service (mail, notes, backup, …) are
            managed by Aurora SSO on the sign-in portal, not here.
          </AlertDescription>
        </Alert>
      </Card>

      <!-- Notifications. Sits high on the page on purpose: it is the
           only card here that changes whether you find out about a
           problem at all. -->
      <NotificationsCard v-if="info?.capabilities?.notifications" />

      <Card v-if="info" class="p-8">
        <h3 class="card-title mb-1">System</h3>
        <p class="card-subtitle mb-4">Metadata</p>
        <dl class="text-sm space-y-2">
          <div class="flex justify-between"><dt class="text-muted-foreground">Hostname</dt><dd class="font-mono">{{ info.hostname }}</dd></div>
          <div class="flex justify-between"><dt class="text-muted-foreground">Domain</dt><dd class="font-mono">{{ info.domain }}</dd></div>
          <div class="flex justify-between"><dt class="text-muted-foreground">LAN IP</dt><dd class="font-mono">{{ info.lanIp }}</dd></div>
          <div class="flex justify-between"><dt class="text-muted-foreground">Kernel</dt><dd class="font-mono">{{ info.kernel }}</dd></div>
          <div class="flex justify-between"><dt class="text-muted-foreground">Docker</dt><dd class="font-mono">{{ info.dockerVersion }}</dd></div>
        </dl>
      </Card>

      <!-- Reverse-proxy routes. Sits directly above LAN aliases because
           the two are the same idea at different layers: this one is
           what Caddy answers to, that one is what the LAN resolves. -->
      <ProxyRoutesCard v-if="info?.capabilities?.proxy" />

      <!-- LAN discovery (2026-08-03 v0.3.x productionize).
           Publishes one avahi A-record per enabled-package vhost so
           notes.aurora.local, code.aurora.local, etc. resolve on the LAN.
           Backend drift-reconciles every 60s; button here forces a
           republish for the "I just enabled a package" case. -->
      <Card class="p-8" data-card="lan-discovery">
        <div class="flex items-baseline justify-between mb-3 gap-4">
          <div>
            <h3 class="card-title mb-1">LAN aliases</h3>
            <p class="text-xs text-muted-foreground mt-1">
              Publishes a friendly name for each enabled app so other devices on
              your network can reach
              <span class="font-mono">&lt;label&gt;.{{ info?.domain ?? 'aurora.local' }}</span>
              with no setup on those devices.
            </p>
          </div>
          <Button
            variant="secondary"
            size="sm"
            :disabled="mdnsReconciling"
            data-test="mdns-reconcile"
            @click="reconcileMdns"
          >{{ mdnsReconciling ? 'Reconciling…' : 'Reconcile' }}</Button>
        </div>

        <Alert v-if="mdnsErr" variant="destructive" class="mb-3" data-test="mdns-error">
          <AlertDescription>{{ mdnsErr }}</AlertDescription>
        </Alert>

        <div
          v-else-if="mdnsLoading && !mdnsAliases.length"
          class="space-y-2 py-3"
          data-state="loading"
        >
          <Skeleton v-for="n in 3" :key="`mdns-sk-${n}`" class="h-7 w-full" />
        </div>

        <div
          v-else-if="mdnsAliases.length === 0"
          class="text-xs text-muted-foreground py-4"
          data-state="empty"
          data-test="mdns-empty"
        >
          No aliases published yet. Enable a package that ships a vhost
          (e.g. Notes, Git, Media) and hit Reconcile.
        </div>

        <Table v-else data-test="mdns-list" class="text-xs">
          <TableHeader>
            <TableRow class="hover:bg-transparent">
              <TableHead class="w-64">Alias</TableHead>
              <TableHead class="w-32">Package</TableHead>
              <TableHead class="w-24">Source</TableHead>
              <TableHead class="w-24">State</TableHead>
              <TableHead>Target IP</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            <TableRow v-for="a in mdnsAliases" :key="a.alias">
              <TableCell class="font-mono align-baseline">
                <a
                  :href="`http://${a.alias}`"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="text-foreground no-underline hover:underline"
                >{{ a.alias }}</a>
              </TableCell>
              <TableCell class="font-mono align-baseline text-muted-foreground">{{ a.pkg }}</TableCell>
              <TableCell class="align-baseline text-muted-foreground">{{ a.source }}</TableCell>
              <TableCell class="align-baseline">
                <Badge :tone="mdnsToneFor(a.state)" :title="a.error ?? undefined">{{ a.state }}</Badge>
              </TableCell>
              <TableCell class="font-mono align-baseline text-muted-foreground">{{ a.targetIp }}</TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </Card>

      <!-- Export / import. Near the bottom because it is a once-a-year
           card, but it is the difference between a reinstall costing an
           evening and costing ten minutes. -->
      <SettingsPortabilityCard />

      <!-- iter-31: audit-log viewer. Consumes GET /api/audit/events
           (iter-30). Kept as an inline card rather than its own route
           so the Settings page is the one place operators check for
           account posture + activity. -->
      <Card class="p-8" data-card="audit-log">
        <div class="flex items-baseline justify-between mb-3 gap-4">
          <div>
            <h3 class="card-title mb-1">Recent activity</h3>
            <p class="text-xs text-muted-foreground mt-1">Newest first, last 100 events.</p>
          </div>
          <div class="flex items-center gap-2">
            <label for="audit-filter" class="sr-only">Filter by action prefix</label>
            <Input
              id="audit-filter"
              v-model="auditFilter"
              placeholder="e.g. security."
              class="h-8 w-40 text-xs"
              data-test="audit-filter"
              @keydown.enter="loadAudit"
            />
            <Button variant="secondary" size="sm" :disabled="auditLoading" @click="loadAudit"
                    data-test="audit-refresh">
              {{ auditLoading ? 'Loading…' : 'Refresh' }}
            </Button>
          </div>
        </div>

        <Alert v-if="auditErr" variant="destructive" class="mb-3" data-test="audit-error">
          <AlertDescription>{{ auditErr }}</AlertDescription>
        </Alert>

        <div
          v-else-if="auditLoading && !auditEvents.length"
          class="space-y-2 py-3"
          data-state="loading"
        >
          <Skeleton v-for="n in 4" :key="`audit-sk-${n}`" class="h-6 w-full" />
        </div>

        <div
          v-else-if="auditEvents.length === 0"
          class="text-xs text-muted-foreground py-4"
          data-state="empty"
          data-test="audit-empty"
        >
          Nothing to show yet. Actions like dismissing a security finding or
          launching a package appear here.
        </div>

        <Table v-else data-test="audit-list" class="font-mono text-xs">
          <TableHeader>
            <TableRow class="hover:bg-transparent">
              <TableHead class="w-40">Time</TableHead>
              <TableHead class="w-56">Action</TableHead>
              <TableHead>Actor · Target</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            <TableRow v-for="e in auditEvents" :key="e.id">
              <TableCell class="text-muted-foreground whitespace-nowrap align-baseline">{{ formatAuditTs(e.ts) }}</TableCell>
              <TableCell class="text-foreground align-baseline">{{ e.action }}</TableCell>
              <TableCell class="text-muted-foreground truncate align-baseline">
                <span v-if="e.user_id !== null" class="text-muted-foreground">user #{{ e.user_id }} · </span>
                {{ e.target ?? '' }}
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </Card>
    </div>

    <!-- Change-password dialog. Lives at the end of the template so
         the Card grid above stays unaffected by the modal's DOM
         insertion order. -->
    <Dialog
      :open="showChangePassword"
      data-test="settings-password-dialog"
      @update:open="(v: boolean) => { if (!v) showChangePassword = false; }"
    >
      <template #title>Change your password</template>
      <template #description>
        You stay signed in on this box after the change. Next time you
        sign in — here or on any app protected by Aurora SSO — use the
        new one.
      </template>

      <div class="space-y-4">
        <Alert v-if="pwErr" variant="destructive" data-test="settings-password-error">
          <AlertDescription>{{ pwErr }}</AlertDescription>
        </Alert>

        <div>
          <Label for="pw-current">Current password</Label>
          <Input
            id="pw-current"
            v-model="pwForm.current"
            type="password"
            autocomplete="current-password"
            data-test="settings-password-current"
          />
        </div>
        <div>
          <Label for="pw-next">New password</Label>
          <Input
            id="pw-next"
            v-model="pwForm.next"
            type="password"
            autocomplete="new-password"
            data-test="settings-password-new"
          />
          <p class="text-xs text-muted-foreground mt-1">
            At least 12 characters.
          </p>
        </div>
        <div>
          <Label for="pw-confirm">Confirm new password</Label>
          <Input
            id="pw-confirm"
            v-model="pwForm.confirm"
            type="password"
            autocomplete="new-password"
            data-test="settings-password-confirm"
          />
        </div>
      </div>

      <template #footer>
        <Button variant="ghost" :disabled="pwBusy" @click="showChangePassword = false">Cancel</Button>
        <Button
          variant="primary"
          :loading="pwBusy"
          data-test="settings-password-submit"
          @click="submitChangePassword"
        >Change password</Button>
      </template>
    </Dialog>
  </section>
</template>
