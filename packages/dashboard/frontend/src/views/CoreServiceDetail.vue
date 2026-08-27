<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ContainersApi, type ContainerInfo } from '@/api/containers';
import { findCoreService } from '@/api/core-services';
import { SsoApi, type SsoNotification } from '@/api/sso';
import { humanCopyForError } from '@/lib/http-error-copy';
import Card from '@/components/ui/Card.vue';
import Badge from '@/components/ui/Badge.vue';
import Button from '@/components/ui/Button.vue';
import Skeleton from '@/components/ui/Skeleton.vue';
import AppIcon from '@/components/AppIcon.vue';
import { Alert, AlertDescription } from '@/components/ui';

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
async function copyOtp(otp: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(otp);
    copied.value = otp;
    window.setTimeout(() => {
      if (copied.value === otp) copied.value = null;
    }, 2000);
  } catch {
    // Clipboard is blocked on insecure origins (this page ships over
    // HTTP on the LAN by default) and in some browsers without a user
    // gesture. The OTP is on screen in a select-all block so failing
    // quietly is fine — a red toast here would suggest the OTP was
    // wrong, which it isn't.
  }
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
                >{{ copied === n.otp ? 'Copied' : 'Copy code' }}</Button>
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
