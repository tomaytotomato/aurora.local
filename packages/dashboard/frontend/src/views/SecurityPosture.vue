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
import { SecurityApi, type SecurityFinding, type SecuritySeverity } from '@/api/security';
import Card from '@/components/ui/Card.vue';
import Alert from '@/components/ui/Alert.vue';
import Badge from '@/components/ui/Badge.vue';
import Button from '@/components/ui/Button.vue';

const system = useSystemStore();

const findings = ref<SecurityFinding[]>([]);
const loading = ref(false);
const err = ref<string | null>(null);
// B4-followup (iter-23): track per-row 'dismissing' state so the button
// disables while the POST is in flight and doesn't spam the backend.
const dismissing = ref<Record<string, boolean>>({});

const scannerLive = computed<boolean>(() =>
  system.info?.capabilities?.securityScanner === true,
);

async function fetchFindings(): Promise<void> {
  loading.value = true;
  err.value = null;
  try {
    findings.value = await SecurityApi.findings();
  } catch (e: unknown) {
    const status = (e as { response?: { status?: number } })?.response?.status;
    if (status === 401 || status === 403) {
      err.value = "You need to sign in again to see the security scan.";
    } else {
      err.value = "Aurora couldn't run the security scan just now.";
    }
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
async function onDismiss(id: string, days: number = 7): Promise<void> {
  if (dismissing.value[id]) return;
  dismissing.value = { ...dismissing.value, [id]: true };
  const prev = findings.value;
  findings.value = findings.value.filter((f) => f.id !== id);
  try {
    await SecurityApi.dismiss(id, days);
  } catch (e: unknown) {
    const status = (e as { response?: { status?: number } })?.response?.status;
    err.value = status === 401 || status === 403
      ? "Session expired — sign in again to dismiss findings."
      : "Aurora couldn't dismiss that finding just now.";
    findings.value = prev;
  } finally {
    const next = { ...dismissing.value };
    delete next[id];
    dismissing.value = next;
  }
}

onMounted(async () => {
  if (!system.info) {
    try { await system.fetchInfo(); } catch { /* silent — the view renders empty */ }
  }
  if (scannerLive.value) await fetchFindings();
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
    <div class="mb-10 flex items-start justify-between gap-6">
      <div>
        <div class="eyebrow mb-2">Security</div>
        <h1 class="mb-3">Security posture</h1>
        <p class="text-ink-3 max-w-2xl">
          Aurora runs a fixed set of opinionated checks against your host,
          containers, and secrets. Every finding has a fix — no silent nags.
        </p>
      </div>
      <div v-if="scannerLive" class="flex items-center gap-2">
        <Badge v-if="counts.high > 0" tone="err" data-test="sec-count-high">
          {{ counts.high }} high
        </Badge>
        <Badge v-if="counts.medium > 0" tone="warn" data-test="sec-count-medium">
          {{ counts.medium }} medium
        </Badge>
        <Badge v-if="counts.low > 0" tone="info" data-test="sec-count-low">
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
      <svg viewBox="0 0 24 24" class="w-8 h-8 text-ink-4 mx-auto mb-4"
           fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
        <path d="M12 3l8 3v6c0 5-4 8-8 9-4-1-8-4-8-9V6z" stroke-linecap="round" stroke-linejoin="round" />
        <path d="M9 12l2 2 4-4" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <h3 class="mb-2">Watching for common misconfigurations</h3>
      <p class="text-sm text-ink-3 max-w-xl mx-auto mb-6">
        The security scanner lands with milestone <span class="font-mono">M4</span>.
        Nothing on this page is a real audit yet — no score, no findings.
      </p>
    </Card>

    <!-- Error state — §5 contract. -->
    <template v-else-if="err">
      <Alert tone="err" class="mb-4" data-test="sec-error">{{ err }}</Alert>
      <div class="mb-6">
        <Button variant="secondary" size="sm" @click="fetchFindings">Try again</Button>
      </div>
    </template>

    <!-- Zero findings — the honest 'all clear' render. -->
    <Card
      v-else-if="!loading && findings.length === 0"
      data-state="empty"
      class="p-10 text-center"
      data-test="sec-empty-clean"
    >
      <svg viewBox="0 0 24 24" class="w-8 h-8 text-ink-4 mx-auto mb-4"
           fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
        <path d="M20 6L9 17l-5-5" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <h3 class="mb-2">Nothing to fix right now</h3>
      <p class="text-sm text-ink-3 max-w-xl mx-auto">
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
                class="text-sm text-ink-2 no-underline hover:underline"
              >Fix it →</router-link>
              <a
                v-else
                :href="f.remediationUrl"
                target="_blank"
                rel="noopener noreferrer"
                class="text-sm text-ink-2 no-underline hover:underline"
              >Learn more ↗</a>
            </template>
            <button
              type="button"
              class="text-sm text-ink-3 hover:text-ink-2 disabled:text-ink-4 disabled:cursor-not-allowed"
              :disabled="!!dismissing[f.id]"
              data-test="sec-dismiss"
              @click="onDismiss(f.id, 7)"
            >
              {{ dismissing[f.id] ? 'Dismissing…' : 'Dismiss 7d' }}
            </button>
          </div>
        </div>
        <p class="text-sm text-ink-3">{{ f.description }}</p>
      </Card>
    </div>
  </section>
</template>
