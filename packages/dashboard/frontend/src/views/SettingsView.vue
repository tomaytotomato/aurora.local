<script setup lang="ts">
import { useAuthStore } from '@/stores/auth';
import { useSystemStore } from '@/stores/system';
import { AuditApi, type AuditEvent } from '@/api/audit';
import { humanCopyForError } from '@/lib/http-error-copy';
import Card from '@/components/ui/Card.vue';
import Button from '@/components/ui/Button.vue';
import Alert from '@/components/ui/AlertLegacy.vue';
import { useRouter } from 'vue-router';
import { computed, onMounted, ref } from 'vue';

const auth = useAuthStore();
const system = useSystemStore();
const router = useRouter();

const info = computed(() => system.info);

async function signOut(): Promise<void> {
  await auth.logout();
  router.push('/login');
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

onMounted(() => { void loadAudit(); });
</script>

<template>
  <section>
    <div class="mb-10">
      <div class="eyebrow mb-2">Preferences</div>
      <h1>Settings</h1>
    </div>

    <div class="space-y-6 max-w-2xl">
      <Card class="p-8">
        <div class="eyebrow mb-2">Admin</div>
        <h3 class="mb-2">Account</h3>
        <div class="text-sm text-ink-3 mb-4">
          Signed in as <span class="font-mono text-ink">{{ auth.session?.username ?? '—' }}</span>.
        </div>
        <Button variant="secondary" size="sm" @click="signOut">Sign out</Button>
      </Card>

      <Card class="p-8">
        <div class="eyebrow mb-2">Passkey</div>
        <h3 class="mb-2">Second factor</h3>
        <Alert tone="info">Passkey enrollment lands in v0.2.</Alert>
      </Card>

      <Card v-if="info" class="p-8">
        <div class="eyebrow mb-2">System</div>
        <h3 class="mb-4">Metadata</h3>
        <dl class="text-sm space-y-2">
          <div class="flex justify-between"><dt class="text-ink-3">Hostname</dt><dd class="font-mono">{{ info.hostname }}</dd></div>
          <div class="flex justify-between"><dt class="text-ink-3">Domain</dt><dd class="font-mono">{{ info.domain }}</dd></div>
          <div class="flex justify-between"><dt class="text-ink-3">LAN IP</dt><dd class="font-mono">{{ info.lanIp }}</dd></div>
          <div class="flex justify-between"><dt class="text-ink-3">Kernel</dt><dd class="font-mono">{{ info.kernel }}</dd></div>
          <div class="flex justify-between"><dt class="text-ink-3">Docker</dt><dd class="font-mono">{{ info.dockerVersion }}</dd></div>
        </dl>
      </Card>

      <!-- iter-31: audit-log viewer. Consumes GET /api/audit/events
           (iter-30). Kept as an inline card rather than its own route
           so the Settings page is the one place operators check for
           account posture + activity. -->
      <Card class="p-8" data-card="audit-log">
        <div class="flex items-baseline justify-between mb-3 gap-4">
          <div>
            <div class="eyebrow mb-2">Audit</div>
            <h3>Recent activity</h3>
            <p class="text-xs text-ink-4 mt-1">Newest first, last 100 events.</p>
          </div>
          <div class="flex items-center gap-2">
            <label for="audit-filter" class="sr-only">Filter by action prefix</label>
            <input
              id="audit-filter"
              v-model="auditFilter"
              placeholder="e.g. security."
              class="text-xs rounded border border-line bg-surface text-ink px-2 py-1 w-40"
              data-test="audit-filter"
              @keydown.enter="loadAudit"
            />
            <Button variant="secondary" size="sm" :disabled="auditLoading" @click="loadAudit"
                    data-test="audit-refresh">
              {{ auditLoading ? 'Loading…' : 'Refresh' }}
            </Button>
          </div>
        </div>

        <Alert v-if="auditErr" tone="err" class="mb-3" data-test="audit-error">{{ auditErr }}</Alert>

        <div
          v-else-if="!auditLoading && auditEvents.length === 0"
          class="text-xs text-ink-4 py-4"
          data-state="empty"
          data-test="audit-empty"
        >
          Nothing to show yet. Actions like dismissing a security finding or
          launching a package appear here.
        </div>

        <ul v-else class="space-y-1.5" data-test="audit-list">
          <li
            v-for="e in auditEvents"
            :key="e.id"
            class="grid grid-cols-[auto_auto_1fr] gap-3 items-baseline text-xs font-mono"
          >
            <span class="text-ink-4 whitespace-nowrap">{{ formatAuditTs(e.ts) }}</span>
            <span class="text-ink-2">{{ e.action }}</span>
            <span class="text-ink-3 truncate">
              <span v-if="e.user_id !== null" class="text-ink-4">user #{{ e.user_id }} · </span>
              {{ e.target ?? '' }}
            </span>
          </li>
        </ul>
      </Card>
    </div>
  </section>
</template>
