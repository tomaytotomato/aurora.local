<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ContainersApi, type ContainerInfo } from '@/api/containers';
import { findCoreService, resolveOpenUrl } from '@/api/core-services';
import { SsoApi, type SsoNotification } from '@/api/sso';
import { StalwartApi, type StalwartAdminCredential, type MailboxCreated } from '@/api/stalwart';
import { useSystemStore } from '@/stores/system';
import { copyToClipboard } from '@/lib/utils';
import { humanCopyForError } from '@/lib/http-error-copy';
import Card from '@/components/ui/Card.vue';
import Badge from '@/components/ui/Badge.vue';
import Button from '@/components/ui/Button.vue';
import Skeleton from '@/components/ui/Skeleton.vue';
import AppIcon from '@/components/AppIcon.vue';
import { Alert, AlertDescription, Input, Label } from '@/components/ui';

// Detail view for one Core service. Shared shell across Caddy, Authelia,
// and Stalwart:
//   - header  (icon + name + running-state badge)
//   - facts   (image, status prose, container name)
//   - logs    (link into the existing /containers/:id/logs surface)
//
// Authelia adds a Notifications panel: the last 5 entries from Authelia's
// filesystem notifier, so the operator can see OTPs and enrollment
// links without shelling into the container. Everything else about
// this page is the same shell so a future Stalwart-specific panel
// (mail admin console link, queue snapshot) drops in the same way.

const route = useRoute();
const router = useRouter();

const service = computed(() => findCoreService(String(route.params.service ?? '')));

// Bounce unknown slugs back to the Core index. This is the same failure
// mode PackageDetail handles by rendering "not found"; here the set is
// static and tiny, so getting one wrong is nearly always a stale link.
//
// Disabled services (Aurora — the dashboard itself) also bounce, but to
// their hinted destination rather than to Core. The grid already tells
// the operator "go to Settings"; URL-typing has to honour that same
// contract or else the hint becomes a lie the moment the operator
// bookmarks the URL.
watch(
  service,
  (svc) => {
    if (!svc) {
      void router.replace('/apps/core');
    } else if (svc.disabled) {
      // Same target the disabled-card hint links to. Kept as replace()
      // so the browser back button lands on Core (where the operator
      // came from) rather than trapping them in a redirect ping-pong.
      void router.replace('/settings');
    }
  },
  { immediate: true },
);

const container = ref<ContainerInfo | null>(null);
const containerErr = ref<string | null>(null);
const containerLoading = ref(true);

async function loadContainer(): Promise<void> {
  const svc = service.value;
  if (!svc) return;
  containerErr.value = null;
  try {
    const list = await ContainersApi.list();
    container.value = list.find((c) => cleanName(c.names) === svc.container) ?? null;
  } catch (e) {
    containerErr.value = humanCopyForError(e, {
      subject: 'this service',
      action: 'load',
    });
  } finally {
    containerLoading.value = false;
  }
}

function cleanName(names: string[]): string {
  const n = names[0] ?? '';
  return n.startsWith('/') ? n.slice(1) : n;
}

// ─── notifications (Authelia only) ─────────────────────────────────

const notifications = ref<SsoNotification[]>([]);
const notificationsErr = ref<string | null>(null);
const notificationsLoading = ref(false);

const isAuthelia = computed(() => service.value?.key === 'authelia');
const isStalwart = computed(() => service.value?.key === 'stalwart');

// ── Stalwart recovery-admin credential (reveal panel) ──────────────────
//
// Bruce landed on Stalwart's mail-admin console for the first time
// and had to shell into the box to `cat packages/core/.env` for the
// recovery admin password. Panel powers a hidden-by-default reveal
// so the plaintext lives on-screen only when the operator asks for
// it.
//
// The lazy fetch matters: pulling the secret on mount would
// materialise it in memory even for operators who never click
// Reveal. First render never asks; the click is what triggers the
// GET. The response is cached in `stalwartCred` for the lifetime of
// the panel so a subsequent "Copy password" click does not need a
// second round trip.
const stalwartCred = ref<StalwartAdminCredential | null>(null);
const stalwartRevealed = ref(false);
const stalwartLoading = ref(false);
const stalwartErr = ref<string | null>(null);

async function toggleStalwartReveal(): Promise<void> {
  if (!isStalwart.value) return;
  if (stalwartRevealed.value) {
    // Hide again without discarding the fetched value — a follow-up
    // reveal on the same page should not incur another round trip.
    stalwartRevealed.value = false;
    return;
  }
  if (stalwartCred.value) {
    stalwartRevealed.value = true;
    return;
  }
  stalwartLoading.value = true;
  stalwartErr.value = null;
  try {
    stalwartCred.value = await StalwartApi.adminSecret();
    stalwartRevealed.value = true;
  } catch (e) {
    const status = (e as { response?: { status?: number } })?.response?.status;
    if (status === 403) {
      // The endpoint is admin-only — non-admin sessions can still see
      // the rest of the page, they just cannot see the credential.
      // Say so plainly.
      stalwartErr.value = 'Only admins can reveal the recovery password.';
    } else {
      stalwartErr.value = humanCopyForError(e, {
        subject: "Stalwart's recovery password",
        action: 'load',
      });
    }
  } finally {
    stalwartLoading.value = false;
  }
}

// ── Edit-password flow ────────────────────────────────────────────
//
// The reveal panel stays visible while the operator is editing so the
// current value is on-screen alongside the new one — the whole point
// of "edit" here is "change it to something better than what I can
// see". Save writes packages/core/.env, refetches through
// StalwartApi.adminSecret() so the panel confirms the write landed,
// and shows an inline note reminding the operator to recreate the
// container so compose picks up the new value (compose interpolates
// env at container-create time; a running container carries the
// value it was created with even after the .env changes).
const stalwartEditing = ref(false);
const stalwartNewPassword = ref('');
const stalwartConfirmPassword = ref('');
const stalwartNewPasswordRevealed = ref(false);
const stalwartSaving = ref(false);
const stalwartSaveError = ref<string | null>(null);
const stalwartSaveSuccess = ref(false);

const MIN_STALWART_SECRET_LENGTH = 12;

function startStalwartEdit(): void {
  stalwartEditing.value = true;
  stalwartNewPassword.value = '';
  stalwartConfirmPassword.value = '';
  stalwartNewPasswordRevealed.value = false;
  stalwartSaveError.value = null;
  stalwartSaveSuccess.value = false;
}

function cancelStalwartEdit(): void {
  // Return to the read-only reveal state. Deliberately keep
  // stalwartCred + stalwartRevealed so the operator can still see the
  // current password after they change their mind — Cancel is not
  // "forget everything".
  stalwartEditing.value = false;
  stalwartNewPassword.value = '';
  stalwartConfirmPassword.value = '';
  stalwartNewPasswordRevealed.value = false;
  stalwartSaveError.value = null;
}

async function saveStalwartSecret(): Promise<void> {
  // Client-side validation. Blocks the API call so a form-fill
  // mistake never hits the backend and never leaves an audit row.
  const next = stalwartNewPassword.value;
  const confirm = stalwartConfirmPassword.value;
  if (!next || !confirm) {
    stalwartSaveError.value = 'Enter and confirm the new password.';
    return;
  }
  if (next.length < MIN_STALWART_SECRET_LENGTH) {
    stalwartSaveError.value =
      `New password must be at least ${MIN_STALWART_SECRET_LENGTH} characters.`;
    return;
  }
  if (next !== confirm) {
    stalwartSaveError.value = 'The two passwords do not match.';
    return;
  }

  stalwartSaving.value = true;
  stalwartSaveError.value = null;
  try {
    await StalwartApi.updateAdminSecret(next);
    // Confirm the write landed by refetching. Same GET the Reveal
    // click uses, so a stale cached value cannot silently lie about
    // the outcome.
    stalwartCred.value = await StalwartApi.adminSecret();
    stalwartSaveSuccess.value = true;
    stalwartEditing.value = false;
    stalwartNewPassword.value = '';
    stalwartConfirmPassword.value = '';
    stalwartNewPasswordRevealed.value = false;
  } catch (e) {
    const status = (e as { response?: { status?: number } })?.response?.status;
    if (status === 403) {
      stalwartSaveError.value = 'Only admins can change the recovery password.';
    } else if (status === 400) {
      const body = (e as { response?: { data?: { message?: string } } })?.response?.data;
      stalwartSaveError.value =
        body?.message ??
        `New password must be at least ${MIN_STALWART_SECRET_LENGTH} characters.`;
    } else {
      stalwartSaveError.value = humanCopyForError(e, {
        subject: "Stalwart's recovery password",
        action: 'save',
      });
    }
  } finally {
    stalwartSaving.value = false;
  }
}

async function copyStalwartSecret(): Promise<void> {
  const secret = stalwartCred.value?.secret;
  if (!secret) return;
  // copyToClipboard tries the Clipboard API first (secure contexts
  // only) and falls back to a hidden-textarea + execCommand('copy')
  // path that works on plain HTTP. The dashboard usually lives at
  // http://aurora.local on the LAN, where navigator.clipboard is
  // undefined — without the fallback the Copy button used to silently
  // do nothing (Bruce's report, 2026-08-27). Returns a boolean so we
  // can render an honest 'Copy failed' state instead of pretending
  // it worked.
  const ok = await copyToClipboard(secret);
  if (ok) {
    stalwartCopied.value = 'ok';
  } else {
    stalwartCopied.value = 'fail';
  }
  window.setTimeout(() => { stalwartCopied.value = null; }, 2500);
}
const stalwartCopied = ref<'ok' | 'fail' | null>(null);

// ── Create mailbox ───────────────────────────────────────────
//
// Aurora auto-provisions the box's mail domain on boot, so the mail
// server is ready with zero setup — but a mailbox needs a password, and
// that is the one genuinely per-operator decision. Rather than make the
// operator invent (and then forget) a strong password, the backend
// GENERATES one and returns it ONCE. This panel is the one-time reveal:
// the operator types the address they want (the domain is the box's own,
// appended automatically), and gets back a working mailbox + a password
// they must copy now because it is never stored in plaintext and cannot
// be shown again — the same contract as the admin-password reset.
const mailboxLocalPart = ref('');
const mailboxCreating = ref(false);
const mailboxError = ref<string | null>(null);
const mailboxResult = ref<MailboxCreated | null>(null);
const mailboxCopied = ref<'ok' | 'fail' | null>(null);

// The box's own domain — same source the Open CTA and Settings read, so
// the address preview here matches what the mailbox actually becomes
// (the backend appends the box domain server-side). Falls back to the
// canonical example before system.info hydrates.
const mailDomain = computed(() => system.info?.domain || 'aurora.local');

// Mirrors the backend's CreateMailboxReq pattern: lowercase letters,
// numbers, dot, dash, underscore; can't start/end with a separator.
// Validated here so an obvious typo never makes a round trip, and the
// button can disable until the field is plausibly valid.
const LOCAL_PART_RE = /^[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?$/;
const mailboxLocalPartValid = computed(
  () => LOCAL_PART_RE.test(mailboxLocalPart.value.trim()) && mailboxLocalPart.value.trim().length <= 64,
);

async function createMailbox(): Promise<void> {
  const local = mailboxLocalPart.value.trim();
  if (!mailboxLocalPartValid.value) {
    mailboxError.value = 'Use lowercase letters, numbers, dot, dash or underscore.';
    return;
  }
  mailboxCreating.value = true;
  mailboxError.value = null;
  mailboxResult.value = null;
  try {
    mailboxResult.value = await StalwartApi.createMailbox(local);
    mailboxLocalPart.value = '';
  } catch (e) {
    const status = (e as { response?: { status?: number } })?.response?.status;
    if (status === 403) {
      mailboxError.value = 'Only admins can create mailboxes.';
    } else if (status === 409) {
      mailboxError.value = `A mailbox “${local}” already exists. Pick a different name.`;
    } else if (status === 502) {
      mailboxError.value = 'The mail server is not reachable right now. Try again in a moment.';
    } else if (status === 400) {
      mailboxError.value = 'Use lowercase letters, numbers, dot, dash or underscore.';
    } else {
      mailboxError.value = humanCopyForError(e, { subject: 'the mailbox', action: 'create' });
    }
  } finally {
    mailboxCreating.value = false;
  }
}

async function copyMailboxPassword(): Promise<void> {
  const pw = mailboxResult.value?.password;
  if (!pw) return;
  const ok = await copyToClipboard(pw);
  mailboxCopied.value = ok ? 'ok' : 'fail';
  window.setTimeout(() => { mailboxCopied.value = null; }, 2500);
}

/** Dismiss the one-time password panel once the operator has copied it. */
function dismissMailboxResult(): void {
  mailboxResult.value = null;
  mailboxCopied.value = null;
}

// Reset reveal state when the operator navigates between services so
// leaving Stalwart and coming back does not still render a stale
// plaintext under the Reveal button.
watch(isStalwart, (nowStalwart) => {
  if (!nowStalwart) {
    stalwartRevealed.value = false;
    stalwartCred.value = null;
    stalwartErr.value = null;
    stalwartEditing.value = false;
    stalwartNewPassword.value = '';
    stalwartConfirmPassword.value = '';
    stalwartNewPasswordRevealed.value = false;
    stalwartSaveError.value = null;
    stalwartSaveSuccess.value = false;
    // Also clear any in-flight / completed mailbox creation so navigating
    // away and back does not leave a stale one-time password on screen.
    mailboxLocalPart.value = '';
    mailboxError.value = null;
    mailboxResult.value = null;
    mailboxCopied.value = null;
  }
});

// Domain comes from /api/system.info — same source Overview and
// Settings both read. Kept as a store rather than fetched here because
// the store already hydrates on app boot; a fresh fetch on this route
// would race the container fetch and blink an unresolved CTA between
// mount and hydration.
const system = useSystemStore();

/**
 * The URL for the service's own UI, or null when we should hide the
 * CTA. Hidden while the container isn't running (a link to an
 * unreachable backend just 502s under Caddy), when the service has no
 * template (Caddy has no browser UI), or before the system store has
 * hydrated a domain (the first paint would otherwise render
 * `https://mail-admin.//`).
 */
const openUrl = computed<string | null>(() => {
  if (!service.value) return null;
  return resolveOpenUrl(
    service.value,
    container.value?.state === 'running',
    system.info?.domain,
  );
});

/** Label for the Open CTA. Falls back to "Open <service label>". */
const openLabel = computed<string>(() => {
  const s = service.value;
  if (!s) return 'Open';
  return s.openLabel ?? `Open ${s.label}`;
});

async function loadNotifications(): Promise<void> {
  if (!isAuthelia.value) return;
  notificationsLoading.value = true;
  notificationsErr.value = null;
  try {
    // 5 is the product decision, not a technical cap. The backend
    // allows up to 20; this panel is deliberately "the last few" so
    // the operator focuses on the one they are waiting on right now.
    notifications.value = await SsoApi.notifications(5);
  } catch (e) {
    notificationsErr.value = humanCopyForError(e, {
      subject: 'notifications',
      action: 'load',
    });
  } finally {
    notificationsLoading.value = false;
  }
}

let poll: number | undefined;

onMounted(() => {
  void loadContainer();
  void loadNotifications();
  // 5s poll: same cadence as the Core index. Authelia's notification
  // file only changes when the operator does something (reset, enroll,
  // OTP), so the poll is idle-cheap and only matters when the operator
  // is actively waiting for a code to land.
  poll = window.setInterval(() => {
    void loadContainer();
    void loadNotifications();
  }, 5000);
});

onUnmounted(() => {
  if (poll) window.clearInterval(poll);
});

// Also re-poll when the route changes between services (e.g. user
// clicks from Caddy → Authelia via a manual URL edit).
watch(
  () => service.value?.key,
  () => {
    container.value = null;
    containerLoading.value = true;
    notifications.value = [];
    void loadContainer();
    void loadNotifications();
  },
);

// ─── helpers ─────────────────────────────────────────────────────

function stateTone(info: ContainerInfo | null): 'ok' | 'neutral' | 'err' {
  if (!info) return 'err';
  if (info.state === 'running') return 'ok';
  if (info.state === 'exited' || info.state === 'dead') return 'err';
  return 'neutral';
}

function stateLabel(info: ContainerInfo | null): string {
  return info?.state ?? 'not running';
}

const copied = ref<string | null>(null);
const copiedFail = ref<string | null>(null);
async function copyOtp(otp: string): Promise<void> {
  // See copyStalwartSecret for the http://aurora.local / secure-context
  // rationale. Same failure mode; same fix.
  const ok = await copyToClipboard(otp);
  if (ok) {
    copied.value = otp;
  } else {
    copiedFail.value = otp;
  }
  window.setTimeout(() => {
    if (copied.value === otp) copied.value = null;
    if (copiedFail.value === otp) copiedFail.value = null;
  }, 2500);
}

/**
 * Format Authelia's timestamp for humans.
 *
 * Authelia writes {@code "2026-08-27 14:04:47.408006237 +0100 BST m=+…"}.
 * `Date.parse` on that string works in every current browser we care
 * about; on the very off chance it fails (parser drift, non-latin
 * locale), we fall back to the raw string so the operator still sees
 * something.
 */
function formatDate(raw: string): string {
  const t = Date.parse(raw);
  if (Number.isNaN(t)) return raw;
  return new Date(t).toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    second: '2-digit',
  });
}

/**
 * The "here is the link that revokes this OTP" URL, when present.
 * Authelia's OTP notifications carry exactly one URL: the revoke link.
 */
function revokeUrl(n: SsoNotification): string | null {
  return n.urls.find((u) => u.includes('/revoke/')) ?? null;
}

// Which entries have their raw body open. Keyed by index because
// duplicate timestamps + duplicate subjects are possible when a burst
// of notifications fires from a single action.
const openBody = ref<Set<number>>(new Set());
function toggleBody(i: number): void {
  const next = new Set(openBody.value);
  if (next.has(i)) next.delete(i);
  else next.add(i);
  openBody.value = next;
}
</script>

<template>
  <section v-if="service" data-test="core-service-detail">
    <div class="mb-6">
      <router-link
        to="/apps/core"
        class="text-sm text-muted-foreground hover:underline no-underline"
      >← Core</router-link>
    </div>

    <div class="flex items-start gap-4 mb-8 on-photo">
      <AppIcon :src="`/icons/${service.icon}.svg`" :label="service.label" class="mt-1" />
      <div class="min-w-0 flex-1">
        <div class="eyebrow mb-1">core service</div>
        <div class="flex items-center gap-3 mb-3">
          <h1 class="mb-0">{{ service.label }}</h1>
          <Badge :tone="stateTone(container)" data-test="core-service-state">
            {{ stateLabel(container) }}
          </Badge>
          <!--
            Open CTA. Same white-pill shape marketplace apps get in
            PackageDetail's hero, and same visibility rule: absent (not
            disabled) when the service has no UI, is not running, or
            has no resolved domain. Hiding beats greying-out because a
            dead CTA reads as "broken" while a missing one just reads
            as "nothing to open from here", which is the truth.
          -->
          <a
            v-if="openUrl"
            :href="openUrl"
            target="_blank"
            rel="noopener noreferrer"
            class="ml-auto inline-flex items-center gap-1.5 rounded-md bg-white/95 text-slate-900 hover:bg-white px-3 py-1.5 text-sm font-medium no-underline shadow-sm"
            data-test="core-service-open"
          >{{ openLabel }} <span aria-hidden="true">↗</span></a>
        </div>
        <p class="max-w-2xl">{{ service.description }}</p>
      </div>
    </div>

    <!-- Basic facts about the container. Kept small on purpose:
         PackageDetail already owns the "everything about a package"
         surface, and duplicating fields here would just drift.  -->
    <Card class="p-6 mb-6" data-test="core-service-facts">
      <div v-if="containerLoading && !container" class="space-y-3">
        <Skeleton class="h-4 w-32" />
        <Skeleton class="h-4 w-64" />
      </div>
      <Alert v-else-if="containerErr" variant="destructive" class="mb-0">
        <AlertDescription>{{ containerErr }}</AlertDescription>
      </Alert>
      <dl v-else-if="container" class="grid grid-cols-1 sm:grid-cols-3 gap-4 text-sm">
        <div>
          <dt class="text-muted-foreground">Container</dt>
          <dd class="font-mono text-foreground">{{ cleanName(container.names) }}</dd>
        </div>
        <div>
          <dt class="text-muted-foreground">Image</dt>
          <dd class="font-mono text-foreground break-all">{{ container.image }}</dd>
        </div>
        <div>
          <dt class="text-muted-foreground">Status</dt>
          <dd class="text-foreground">{{ container.status }}</dd>
        </div>
      </dl>
      <p v-else class="text-sm text-muted-foreground">
        No container with the name <span class="font-mono">{{ service.container }}</span>
        is running under the aurora compose project.
      </p>

      <div v-if="container" class="mt-4 flex flex-wrap gap-3">
        <router-link
          :to="`/containers/${encodeURIComponent(cleanName(container.names))}/logs`"
          class="no-underline"
        >
          <Button variant="secondary" size="sm">View logs →</Button>
        </router-link>
      </div>
    </Card>

    <!-- Stalwart-specific: reveal the recovery-admin credential.
         Bruce landed on the mail-admin console for the first time and
         had to shell in to read packages/core/.env for the password.
         The credential is hidden by default and only fetched when the
         operator clicks Reveal, so the plaintext never touches this
         page for anyone who never asks. Admin-only on the server. -->
    <div v-if="isStalwart" data-test="stalwart-admin-panel" class="mb-6">
      <div class="mb-3 on-photo">
        <h2 class="mb-1">Recovery admin</h2>
        <p class="text-sm">
          Sign in to the mail-admin console with these credentials the
          first time you visit. Kept in
          <code class="font-mono">packages/core/.env</code>. Only admins can reveal it.
        </p>
      </div>

      <Card class="p-6">
        <dl class="grid grid-cols-1 sm:grid-cols-[max-content_1fr] gap-x-6 gap-y-3 text-sm">
          <dt class="text-muted-foreground">Username</dt>
          <dd class="font-mono text-foreground" data-test="stalwart-admin-username">
            {{ stalwartCred?.username ?? 'admin' }}
          </dd>

          <dt class="text-muted-foreground">Password</dt>
          <dd class="flex items-center gap-3 flex-wrap">
            <code
              class="font-mono text-foreground select-all"
              data-test="stalwart-admin-secret"
            >{{ stalwartRevealed && stalwartCred ? stalwartCred.secret : '••••••••••••••' }}</code>
            <Button
              variant="secondary"
              size="sm"
              :loading="stalwartLoading"
              data-test="stalwart-admin-reveal"
              @click="toggleStalwartReveal"
            >{{ stalwartRevealed ? 'Hide' : 'Reveal' }}</Button>
            <Button
              v-if="stalwartRevealed && stalwartCred"
              variant="secondary"
              size="sm"
              data-test="stalwart-admin-copy"
              @click="copyStalwartSecret"
            >{{ stalwartCopied === 'ok' ? 'Copied' : stalwartCopied === 'fail' ? 'Copy failed' : 'Copy' }}</Button>
            <Button
              v-if="stalwartRevealed && stalwartCred && !stalwartEditing"
              variant="secondary"
              size="sm"
              data-test="stalwart-admin-edit"
              @click="startStalwartEdit"
            >Edit password</Button>
          </dd>
        </dl>

        <!-- Success alert after a save. Tells the operator the write
             landed AND that compose still has to pick up the new value
             — a Stalwart container created before this rotation keeps
             the old value until it is recreated. -->
        <Alert
          v-if="stalwartSaveSuccess"
          class="mt-4"
          data-test="stalwart-admin-save-success"
        >
          <AlertDescription>
            Password saved. The container needs to be recreated to pick
            up the new value — run
            <code class="font-mono">./scripts/up.sh core</code> on the
            host. The Reveal panel above already shows the new value
            for verification.
          </AlertDescription>
        </Alert>

        <!-- Inline edit form. Deliberately not a modal: the current
             (soon-to-be-old) value stays visible above so the operator
             can see what they are replacing. -->
        <div
          v-if="stalwartEditing"
          class="mt-6 pt-6 border-t border-border space-y-4"
          data-test="stalwart-admin-edit-form"
        >
          <div>
            <Label for="stalwart-new-password" class="mb-1 block">New password</Label>
            <div class="flex items-center gap-2">
              <Input
                id="stalwart-new-password"
                :type="stalwartNewPasswordRevealed ? 'text' : 'password'"
                :model-value="stalwartNewPassword"
                autocomplete="new-password"
                data-test="stalwart-admin-new-password"
                @update:model-value="stalwartNewPassword = $event"
              />
              <Button
                type="button"
                variant="secondary"
                size="sm"
                data-test="stalwart-admin-new-password-reveal"
                @click="stalwartNewPasswordRevealed = !stalwartNewPasswordRevealed"
              >{{ stalwartNewPasswordRevealed ? 'Hide' : 'Show' }}</Button>
            </div>
            <p class="text-xs text-muted-foreground mt-1">
              At least {{ MIN_STALWART_SECRET_LENGTH }} characters.
            </p>
          </div>
          <div>
            <Label for="stalwart-confirm-password" class="mb-1 block">Confirm new password</Label>
            <Input
              id="stalwart-confirm-password"
              :type="stalwartNewPasswordRevealed ? 'text' : 'password'"
              :model-value="stalwartConfirmPassword"
              autocomplete="new-password"
              data-test="stalwart-admin-confirm-password"
              @update:model-value="stalwartConfirmPassword = $event"
            />
          </div>

          <Alert
            v-if="stalwartSaveError"
            variant="destructive"
            data-test="stalwart-admin-save-error"
          >
            <AlertDescription>{{ stalwartSaveError }}</AlertDescription>
          </Alert>

          <div class="flex items-center gap-3">
            <Button
              :loading="stalwartSaving"
              data-test="stalwart-admin-save"
              @click="saveStalwartSecret"
            >Save</Button>
            <Button
              variant="secondary"
              :disabled="stalwartSaving"
              data-test="stalwart-admin-cancel"
              @click="cancelStalwartEdit"
            >Cancel</Button>
          </div>
        </div>

        <Alert
          v-if="stalwartErr"
          variant="destructive"
          class="mt-4"
          data-test="stalwart-admin-error"
        >
          <AlertDescription>{{ stalwartErr }}</AlertDescription>
        </Alert>

        <!-- The DEFAULT source is the compose fallback (aurora-change-me)
             which every attacker on the LAN already knows. Say so
             prominently and point at the rotation path. -->
        <Alert
          v-if="stalwartRevealed && stalwartCred?.source === 'DEFAULT'"
          variant="destructive"
          class="mt-4"
          data-test="stalwart-admin-default-warning"
        >
          <AlertDescription>
            This is the compose fallback — every box that skipped
            rotation runs with the same value. Set
            <code class="font-mono">STALWART_ADMIN_SECRET</code> in
            <code class="font-mono">packages/core/.env</code> and run
            <code class="font-mono">./scripts/rotate-secrets.sh --apply</code>
            to fix, then recreate the Stalwart container so compose
            picks up the new value.
          </AlertDescription>
        </Alert>
      </Card>
    </div>

    <!-- Stalwart-specific: create a mailbox. Aurora auto-provisions the
         mail domain, so this is the one per-operator step. The backend
         generates the password and returns it once; this panel is the
         one-time reveal + copy. Admin-only on the server. -->
    <div v-if="isStalwart" data-test="stalwart-mailbox-panel" class="mb-6">
      <div class="mb-3 on-photo">
        <h2 class="mb-1">Create a mailbox</h2>
        <p class="text-sm">
          Add an email address on
          <code class="font-mono">{{ mailDomain }}</code>. Aurora sets a
          strong password for you and shows it once — copy it before you
          close the panel.
        </p>
      </div>

      <Card class="p-6">
        <!-- The one-time password result. Shown after a successful create;
             the password is never retrievable again, so it stays until the
             operator explicitly dismisses it. -->
        <div
          v-if="mailboxResult"
          data-test="stalwart-mailbox-result"
          class="space-y-4"
        >
          <Alert data-test="stalwart-mailbox-success">
            <AlertDescription>
              Mailbox <strong class="font-mono">{{ mailboxResult.email }}</strong>
              is ready. This password is shown once and cannot be recovered —
              copy it now and give it to whoever owns the mailbox.
            </AlertDescription>
          </Alert>

          <dl class="grid grid-cols-1 sm:grid-cols-[max-content_1fr] gap-x-6 gap-y-3 text-sm">
            <dt class="text-muted-foreground">Address</dt>
            <dd class="font-mono text-foreground select-all" data-test="stalwart-mailbox-email">
              {{ mailboxResult.email }}
            </dd>

            <dt class="text-muted-foreground">Password</dt>
            <dd class="flex items-center gap-3 flex-wrap">
              <code
                class="font-mono text-foreground select-all"
                data-test="stalwart-mailbox-password"
              >{{ mailboxResult.password }}</code>
              <Button
                variant="secondary"
                size="sm"
                data-test="stalwart-mailbox-copy"
                @click="copyMailboxPassword"
              >{{ mailboxCopied === 'ok' ? 'Copied' : mailboxCopied === 'fail' ? 'Copy failed' : 'Copy' }}</Button>
            </dd>
          </dl>

          <div class="flex items-center gap-3 pt-2">
            <Button
              variant="secondary"
              size="sm"
              data-test="stalwart-mailbox-done"
              @click="dismissMailboxResult"
            >Done — I've copied it</Button>
          </div>
        </div>

        <!-- The create form. Hidden while a result is on screen so the
             one-time password is not competing with a fresh form. -->
        <div v-else class="space-y-4">
          <div>
            <Label for="stalwart-mailbox-localpart" class="mb-1 block">Email address</Label>
            <div class="flex items-center gap-2 flex-wrap">
              <Input
                id="stalwart-mailbox-localpart"
                :model-value="mailboxLocalPart"
                placeholder="e.g. bruce"
                autocomplete="off"
                class="max-w-[16rem]"
                data-test="stalwart-mailbox-localpart"
                @update:model-value="mailboxLocalPart = $event"
                @keyup.enter="createMailbox"
              />
              <span class="font-mono text-muted-foreground">@{{ mailDomain }}</span>
            </div>
            <p class="text-xs text-muted-foreground mt-1">
              Lowercase letters, numbers, dot, dash or underscore.
            </p>
          </div>

          <Alert
            v-if="mailboxError"
            variant="destructive"
            data-test="stalwart-mailbox-error"
          >
            <AlertDescription>{{ mailboxError }}</AlertDescription>
          </Alert>

          <Button
            :loading="mailboxCreating"
            :disabled="!mailboxLocalPartValid || mailboxCreating"
            data-test="stalwart-mailbox-create"
            @click="createMailbox"
          >Create mailbox</Button>
        </div>
      </Card>
    </div>

    <!-- Authelia-specific: notifications from the filesystem notifier.
         This is the whole reason /apps/core got a proper detail view:
         Authelia's OTPs and enrollment links land in a root-owned file
         inside the container, and without surfacing them the operator
         has to `docker exec cat /data/notification.txt` — which is
         exactly the footgun the M0.5 wizard step exists to close. -->
    <div v-if="isAuthelia" data-test="authelia-notifications">
      <div class="flex items-center justify-between mb-3 on-photo">
        <div>
          <h2 class="mb-1">Notifications</h2>
          <p class="text-sm">
            The most recent five entries Authelia has written. One-time codes and
            enrollment links land here so you don't have to open a shell to find
            them.
            <span class="opacity-80">Updates every few seconds.</span>
          </p>
        </div>
        <Button
          v-if="!notificationsLoading"
          variant="secondary"
          size="sm"
          data-test="authelia-notifications-refresh"
          @click="loadNotifications"
        >Refresh</Button>
      </div>

      <div
        v-if="notificationsLoading && !notifications.length"
        class="space-y-3"
        data-test="authelia-notifications-loading"
      >
        <Skeleton class="h-24 w-full" />
        <Skeleton class="h-24 w-full" />
      </div>

      <Alert
        v-else-if="notificationsErr"
        variant="destructive"
        class="mb-3"
        data-test="authelia-notifications-error"
      >
        <AlertDescription>{{ notificationsErr }}</AlertDescription>
      </Alert>

      <Card
        v-else-if="!notifications.length"
        class="p-8 text-center"
        data-test="authelia-notifications-empty"
      >
        <p class="text-sm text-foreground mb-1">No notifications yet.</p>
        <p class="text-xs text-muted-foreground">
          Authelia writes here when you enroll a factor, reset a password, or ask
          it to confirm your identity. Trigger any of those and it will appear.
        </p>
      </Card>

      <ol v-else class="space-y-4" data-test="authelia-notifications-list">
        <li
          v-for="(n, i) in notifications"
          :key="`${n.date}-${i}`"
          data-test="authelia-notification"
        >
          <Card class="p-6">
            <div class="flex items-start justify-between gap-4 mb-3">
              <div class="min-w-0">
                <div class="eyebrow mb-1">{{ formatDate(n.date) }}</div>
                <div class="text-foreground font-medium">{{ n.subject }}</div>
                <div class="text-xs text-muted-foreground mt-0.5 truncate">
                  For {{ n.recipient }}
                </div>
              </div>
              <Badge v-if="n.otp" tone="info">one-time code</Badge>
              <Badge v-else tone="neutral">link</Badge>
            </div>

            <!-- OTP: the whole reason this panel exists. Rendered big
                 and copyable, with a plain "type this into the browser
                 prompt" caption so the operator isn't left guessing
                 what to do with the code. -->
            <div
              v-if="n.otp"
              class="border border-border rounded-lg p-4 bg-muted/40 mb-3"
              data-test="authelia-notification-otp"
            >
              <div class="eyebrow mb-2">Your one-time code</div>
              <div class="flex items-center gap-3 flex-wrap">
                <code
                  class="font-mono text-2xl tracking-widest select-all text-foreground"
                >{{ n.otp }}</code>
                <Button
                  size="sm"
                  variant="secondary"
                  data-test="authelia-notification-copy"
                  @click="copyOtp(n.otp)"
                >{{ copied === n.otp ? 'Copied' : copiedFail === n.otp ? 'Copy failed' : 'Copy code' }}</Button>
              </div>
              <p class="text-xs text-muted-foreground mt-2">
                Type this into the code prompt Authelia is showing in your
                browser. If you didn't ask for it, use the revoke link below.
              </p>
            </div>

            <!-- Enrollment / password-reset link. Same actionability as
                 an OTP: whoever opens it first binds an authenticator
                 to the account, which is why this whole endpoint is
                 authenticated. -->
            <div
              v-else-if="n.urls.length"
              class="border border-border rounded-lg p-4 bg-muted/40 mb-3"
              data-test="authelia-notification-link"
            >
              <div class="eyebrow mb-2">Confirmation link</div>
              <a
                :href="n.urls[0]"
                target="_blank"
                rel="noopener noreferrer"
                class="font-mono text-xs break-all text-foreground hover:underline"
              >{{ n.urls[0] }}</a>
              <div class="mt-3">
                <a :href="n.urls[0]" target="_blank" rel="noopener noreferrer">
                  <Button size="sm">Open link ↗</Button>
                </a>
              </div>
            </div>

            <div class="flex flex-wrap items-center gap-3">
              <a
                v-if="revokeUrl(n)"
                :href="revokeUrl(n) ?? undefined"
                target="_blank"
                rel="noopener noreferrer"
                class="text-xs text-foreground underline"
                data-test="authelia-notification-revoke"
              >Revoke this code ↗</a>
              <button
                type="button"
                class="text-xs text-muted-foreground hover:text-foreground underline"
                data-test="authelia-notification-toggle-body"
                @click="toggleBody(i)"
              >{{ openBody.has(i) ? 'Hide details' : 'Show details' }}</button>
            </div>

            <pre
              v-if="openBody.has(i)"
              class="mt-4 p-4 bg-muted/40 border border-border rounded-md text-xs
                     text-muted-foreground whitespace-pre-wrap font-mono"
              data-test="authelia-notification-body"
            >{{ n.body }}</pre>
          </Card>
        </li>
      </ol>
    </div>
  </section>
</template>
