<script setup lang="ts">
// B4 (v0.3, iter-14): live security-findings view. Replaces the M4
// empty-state stub for the /security route when SystemInfo.capabilities.
// securityScanner is true. Backend endpoint: GET /api/security/findings.
//
// UX contract:
//   - §5 error state (network / auth) — human copy, no axios strings.
//   - §4 empty state ("Nothing to fix right now.") — the honest zero-
//     findings render, not a fabricated score.
//   - Per-finding card: severity Badge, title, description, remediation
//     link (in-app router-link if the URL starts with '/', otherwise
//     new-tab <a rel="noopener noreferrer">).
//   - Refresh button re-hits the endpoint.
//   - Findings arrive pre-sorted; the view does not re-order.
//
// The old empty-state block (scanner off) is preserved verbatim so a
// downgrade of the capability flag still renders warm empty copy.
import { computed, onMounted, ref } from 'vue';
import { useSystemStore } from '@/stores/system';
import { SecurityApi, type SecurityFinding, type SecuritySeverity, type DismissalRow } from '@/api/security';
import { humanCopyForError } from '@/lib/http-error-copy';
import Card from '@/components/ui/Card.vue';
import { Alert, AlertDescription, Select } from '@/components/ui';
import Badge from '@/components/ui/Badge.vue';
import Button from '@/components/ui/Button.vue';

const system = useSystemStore();

const findings = ref<SecurityFinding[]>([]);
const loading = ref(false);
const err = ref<string | null>(null);
// B4-followup (iter-23): track per-row 'dismissing' state so the button
// disables while the POST is in flight and doesn't spam the backend.
const dismissing = ref<Record<string, boolean>>({});

// B4-followup (iter-26): snooze duration picker per finding. Fixed 7d
// was the iter-23 shipping default; this iter unlocks the days parameter
// the backend already accepts. Kept out of a modal — the compact inline
// select keeps the Fix-it row on one line while still being reachable
// via keyboard tab order.
type SnoozeChoice = { key: string; label: string; days: number | null };
const SNOOZE_CHOICES: readonly SnoozeChoice[] = [
  { key: '1d',  label: '1 day',    days: 1 },
  { key: '7d',  label: '7 days',   days: 7 },
  { key: '30d', label: '30 days',  days: 30 },
  { key: '90d', label: '90 days',  days: 90 },
  { key: 'perm', label: 'Permanent', days: null },
];
// Per-finding selected choice. Defaults to 7d for parity with iter-23.
const snoozeSelection = ref<Record<string, string>>({});
function chosen(fid: string): SnoozeChoice {
  const k = snoozeSelection.value[fid] ?? '7d';
  return SNOOZE_CHOICES.find((c) => c.key === k) ?? SNOOZE_CHOICES[1];
}

// B4-followup (iter-25): suppressed-findings management view. Lives
// below the active list on the same page rather than under Settings so
// the operator sees dismissals in context ("what did I already hide?").
const suppressed = ref<DismissalRow[]>([]);
const suppressedOpen = ref<boolean>(false);
const restoring = ref<Record<string, boolean>>({});

function formatIso(iso: string | null | undefined): string {
  if (!iso) return '\u2014';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit',
  });
}

function dismissalExpiryLabel(row: DismissalRow): string {
  if (!row.expires_at) return 'permanent';
  const expires = new Date(row.expires_at);
  if (Number.isNaN(expires.getTime())) return 'unknown';
  const now = Date.now();
  const deltaMs = expires.getTime() - now;
  if (deltaMs <= 0) return 'expired';
  const hours = deltaMs / 3_600_000;
  if (hours < 24) return `${hours.toFixed(0)}h left`;
  return `${Math.round(hours / 24)}d left`;
}

const scannerLive = computed<boolean>(() =>
  system.info?.capabilities?.securityScanner === true,
);

async function fetchFindings(): Promise<void> {
  loading.value = true;
  err.value = null;
  try {
    findings.value = await SecurityApi.findings();
  } catch (e: unknown) {
    err.value = humanCopyForError(e, {
      subject: 'the security scan',
      action: 'run',
    });
    findings.value = [];
  } finally {
    loading.value = false;
  }
}

/**
 * B4-followup (iter-23): dismiss + optimistic remove. On success the
 * row leaves the list; on failure we surface a short error banner + roll
 * back the optimistic update via a re-fetch.
 */
async function onDismiss(id: string, days: number | null = 7): Promise<void> {
  if (dismissing.value[id]) return;
  dismissing.value = { ...dismissing.value, [id]: true };
  const prev = findings.value;
  findings.value = findings.value.filter((f) => f.id !== id);
  try {
    // iter-26: null days → permanent dismissal (backend accepts undefined).
    await SecurityApi.dismiss(id, days ?? undefined);
    // iter-25: after dismiss, refresh suppressed so the toggle count
    // updates without waiting for the user to open the section.
    void fetchSuppressed();
  } catch (e: unknown) {
    err.value = humanCopyForError(e, {
      subject: 'that finding',
      action: 'dismiss',
    });
    findings.value = prev;
  } finally {
    const next = { ...dismissing.value };
    delete next[id];
    dismissing.value = next;
  }
}

async function fetchSuppressed(): Promise<void> {
  try {
    suppressed.value = await SecurityApi.listDismissals();
  } catch {
    // Silent — the section stays collapsed / empty; main feed still works.
    suppressed.value = [];
  }
}

async function onRestore(id: string): Promise<void> {
  if (restoring.value[id]) return;
  restoring.value = { ...restoring.value, [id]: true };
  const prev = suppressed.value;
  suppressed.value = suppressed.value.filter((r) => r.finding_id !== id);
  try {
    await SecurityApi.restore(id);
    // Re-fetch active findings so the restored one reappears in the
    // main feed on the next tick without a page reload.
    await fetchFindings();
  } catch (e: unknown) {
    err.value = humanCopyForError(e, {
      subject: 'that finding',
      action: 'restore',
    });
    suppressed.value = prev;
  } finally {
    const next = { ...restoring.value };
    delete next[id];
    restoring.value = next;
  }
}

function toggleSuppressed(): void {
  suppressedOpen.value = !suppressedOpen.value;
  if (suppressedOpen.value && suppressed.value.length === 0) {
    void fetchSuppressed();
  }
}

onMounted(async () => {
  if (!system.info) {
    try { await system.fetchInfo(); } catch { /* silent — the view renders empty */ }
  }
  if (scannerLive.value) {
    await fetchFindings();
    // iter-25: pre-fetch suppressed so the toggle count is accurate
    // without waiting for the user to open the section.
    void fetchSuppressed();
  }
});

// Severity → Badge tone. Unknown severities default to neutral so a
// future 'critical' or 'info' introduced by a new rule doesn't crash
// the UI, just misses the coloured tone until the map is updated.
type BadgeTone = 'ok' | 'warn' | 'err' | 'info' | 'neutral';
function toneFor(severity: SecuritySeverity): BadgeTone {
  switch (severity) {
    case 'high': return 'err';
    case 'medium': return 'warn';
    case 'low': return 'info';
    default: return 'neutral';
  }
}

function isInternalHref(url: string | null): boolean {
  return !!url && url.startsWith('/');
}

// Aggregate counts for the header pill. Purely derived; no fabricated
// score — the old "78" number is gone for good.
const counts = computed(() => {
  const c: Record<string, number> = { high: 0, medium: 0, low: 0, other: 0 };
  for (const f of findings.value) {
    if (f.severity === 'high') c.high++;
    else if (f.severity === 'medium') c.medium++;
    else if (f.severity === 'low') c.low++;
    else c.other++;
  }
  return c;
});
</script>

<template>
  <section data-view="security-posture">
    <div class="mb-10 flex items-start justify-between gap-6 on-photo">
      <div>
        <div class="eyebrow mb-2">Security</div>
        <h1 class="mb-3">Security posture</h1>
        <p class="text-muted-foreground max-w-2xl">
          Aurora runs a fixed set of opinionated checks against your host,
          containers, and secrets. Every finding has a fix — no silent nags.
        </p>
      </div>
      <div v-if="scannerLive" class="flex items-center gap-2">
        <Badge v-if="counts.high > 0" tone="err" class="bg-card" data-test="sec-count-high">
          {{ counts.high }} high
        </Badge>
        <Badge v-if="counts.medium > 0" tone="warn" class="bg-card" data-test="sec-count-medium">
          {{ counts.medium }} medium
        </Badge>
        <Badge v-if="counts.low > 0" tone="info" class="bg-card" data-test="sec-count-low">
          {{ counts.low }} low
        </Badge>
        <Button variant="secondary" size="sm" :disabled="loading" @click="fetchFindings"
                data-test="sec-refresh">
          {{ loading ? 'Scanning…' : 'Refresh' }}
        </Button>
      </div>
    </div>

    <!--
      Scanner off — v0.2.x default. Preserved verbatim so a capability
      downgrade still renders the honest empty state.
    -->
    <Card v-if="!scannerLive" data-state="empty" class="p-10 text-center" data-test="security-empty">
      <svg viewBox="0 0 24 24" class="w-8 h-8 text-muted-foreground mx-auto mb-4"
           fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
        <path d="M12 3l8 3v6c0 5-4 8-8 9-4-1-8-4-8-9V6z" stroke-linecap="round" stroke-linejoin="round" />
        <path d="M9 12l2 2 4-4" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <h3 class="mb-2">Watching for common misconfigurations</h3>
      <p class="text-sm text-muted-foreground max-w-xl mx-auto mb-6">
        The security scanner lands with milestone <span class="font-mono">M4</span>.
        Nothing on this page is a real audit yet — no score, no findings.
      </p>
    </Card>

    <!-- Error state — §5 contract. -->
    <Card v-else-if="err" class="p-8">
      <Alert variant="destructive" class="mb-4" data-test="sec-error">
        <AlertDescription>{{ err }}</AlertDescription>
      </Alert>
      <Button variant="secondary" size="sm" @click="fetchFindings">Try again</Button>
    </Card>

    <!-- Zero findings — the honest 'all clear' render. -->
    <Card
      v-else-if="!loading && findings.length === 0"
      data-state="empty"
      class="p-10 text-center"
      data-test="sec-empty-clean"
    >
      <svg viewBox="0 0 24 24" class="w-8 h-8 text-muted-foreground mx-auto mb-4"
           fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
        <path d="M20 6L9 17l-5-5" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <h3 class="mb-2">Nothing to fix right now</h3>
      <p class="text-sm text-muted-foreground max-w-xl mx-auto">
        Every check Aurora runs came back clean. This page updates when
        you refresh; nothing is polled in the background.
      </p>
    </Card>

    <!-- Findings list. -->
    <div v-else class="space-y-3" data-test="sec-findings">
      <Card
        v-for="f in findings"
        :key="f.id"
        class="p-6"
        :data-severity="f.severity"
        :data-finding-id="f.id"
      >
        <div class="flex items-start justify-between gap-4 mb-2">
          <div class="flex items-center gap-2">
            <Badge :tone="toneFor(f.severity)" class="uppercase">{{ f.severity }}</Badge>
            <h3 class="text-base font-medium">{{ f.title }}</h3>
          </div>
          <div class="flex items-center gap-3 whitespace-nowrap">
            <template v-if="f.remediationUrl">
              <router-link
                v-if="isInternalHref(f.remediationUrl)"
                :to="f.remediationUrl"
                class="text-sm text-foreground no-underline hover:underline"
              >Fix it →</router-link>
              <a
                v-else
                :href="f.remediationUrl"
                target="_blank"
                rel="noopener noreferrer"
                class="text-sm text-foreground no-underline hover:underline"
              >Learn more ↗</a>
            </template>
            <Select
              :model-value="snoozeSelection[f.id] ?? '7d'"
              :options="SNOOZE_CHOICES.map((c) => ({ value: c.key, label: c.label }))"
              :disabled="!!dismissing[f.id]"
              :aria-label="`Snooze duration for ${f.title}`"
              class="h-8 w-32 text-xs"
              data-test="sec-snooze-picker"
              @update:model-value="snoozeSelection[f.id] = $event as string"
            />
            <button
              type="button"
              class="text-sm text-muted-foreground hover:text-foreground disabled:text-muted-foreground disabled:cursor-not-allowed"
              :disabled="!!dismissing[f.id]"
              data-test="sec-dismiss"
              @click="onDismiss(f.id, chosen(f.id).days)"
            >
              {{ dismissing[f.id] ? 'Dismissing…' : 'Dismiss' }}
            </button>
          </div>
        </div>
        <p class="text-sm text-muted-foreground">{{ f.description }}</p>
      </Card>
    </div>

    <!--
      B4-followup (iter-25): collapsed 'Suppressed findings' section under
      the active list. Renders only when the scanner is capable so a
      capability downgrade doesn't leak an empty toggle. Empty state is
      honest — 'nothing has been dismissed'.
    -->
    <div v-if="scannerLive" class="mt-8" data-test="sec-suppressed-section">
      <button
        type="button"
        class="text-sm text-muted-foreground hover:text-foreground flex items-center gap-2"
        data-test="sec-suppressed-toggle"
        :aria-expanded="suppressedOpen"
        @click="toggleSuppressed"
      >
        <span class="font-mono" aria-hidden="true">{{ suppressedOpen ? '▾' : '▸' }}</span>
        Suppressed findings
        <span class="text-muted-foreground">({{ suppressed.length }})</span>
      </button>
      <div v-if="suppressedOpen" class="mt-3 space-y-2" data-test="sec-suppressed-list">
        <p v-if="suppressed.length === 0" class="text-xs text-muted-foreground">
          Nothing has been dismissed. Dismissed findings show up here so you
          can bring them back at any time.
        </p>
        <div
          v-for="row in suppressed"
          :key="row.finding_id"
          class="flex items-start justify-between gap-3 border border-border rounded-md px-4 py-3"
        >
          <div class="min-w-0 text-sm">
            <div class="font-mono text-foreground truncate">{{ row.finding_id }}</div>
            <div class="text-xs text-muted-foreground mt-0.5">
              dismissed {{ formatIso(row.dismissed_at) }} ·
              {{ dismissalExpiryLabel(row) }}
              <span v-if="row.reason">· <em>{{ row.reason }}</em></span>
            </div>
          </div>
          <button
            type="button"
            class="text-sm text-foreground hover:text-foreground whitespace-nowrap disabled:text-muted-foreground disabled:cursor-not-allowed"
            :disabled="!!restoring[row.finding_id]"
            data-test="sec-restore"
            @click="onRestore(row.finding_id)"
          >
            {{ restoring[row.finding_id] ? 'Restoring…' : 'Restore' }}
          </button>
        </div>
      </div>
    </div>
  </section>
</template>
