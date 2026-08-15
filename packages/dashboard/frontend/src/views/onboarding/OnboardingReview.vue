<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useOnboardingStore } from '@/stores/onboarding';
import { OnboardingApi, type InstallPlan } from '@/api/onboarding';
import Button from '@/components/ui/Button.vue';
import { Alert, AlertDescription } from '@/components/ui';
import { prettyPackageName } from '@/lib/packageName';
import { humanCopyForError } from '@/lib/http-error-copy';

const store = useOnboardingStore();
const router = useRouter();

const plan = ref<InstallPlan | null>(null);
const planErr = ref<string | null>(null);
const installing = ref(false);
const installErr = ref<string | null>(null);
// Iter-3 §2a.ii: parse the structured 500 body `{error, message}` from
// /install|/apply|/launch. `installErr` renders `message` in the alert;
// `installErrCode` is retained for future CTA-copy tweaks.
const installErrCode = ref<string | null>(null);
const logLines = ref<string[]>([]);

onMounted(async () => {
  // Ensure the store is hydrated in case the user landed here directly
  // (e.g. via a bookmark). Cheap no-op if hydrate already ran.
  if (!store.hydrated) {
    try { await store.hydrate(); } catch { /* fall through */ }
  }
  try {
    plan.value = await OnboardingApi.plan();
  } catch (e) {
    planErr.value = "Aurora couldn't reach the backend, so this is a local preview only.";
  }
});

// Source of truth for the "packages" row on the summary card. Never derive
// from plan alone: if /plan fails the packages column would blank out even
// though the user made a selection. Prefer the plan (server truth) when
// present, fall back to the local store.
const packagesToShow = computed<string[]>(() => {
  const fromPlan = plan.value?.packagesToEnable ?? [];
  if (fromPlan.length > 0) return fromPlan;
  return store.selectedPackages ?? [];
});

// Same pattern for vhosts: prefer plan, fall back to a naive derivation
// so the user sees *something* rather than an empty column.
const vhostsToShow = computed<string[]>(() => {
  const fromPlan = plan.value?.vhosts ?? [];
  if (fromPlan.length > 0) return fromPlan;
  const d = store.domain;
  if (!d) return [];
  return packagesToShow.value
    .filter((p) => p !== 'core')
    .map((p) => `${p}.${d}`);
});

const portsToShow = computed<number[]>(() => plan.value?.ports ?? []);
const warningsToShow = computed<string[]>(() => plan.value?.warnings ?? []);

async function install(): Promise<void> {
  installing.value = true;
  installErr.value = null;
  installErrCode.value = null;
  // Iter-3 §2a.ii: seed the log region at t=0 so the role="log" panel is
  // non-empty within 3s of the Install click. error-recovery.spec.ts asserts.
  logLines.value = ['Aurora is starting your services…'];
  try {
    // Belt & braces: PATCH the final selection one more time in case the
    // user jumped straight to review via the sidebar without hitting
    // Continue on packages/dns.
    logLines.value.push('› Persisting draft selection…');
    await store.patchDraft({
      enabled_packages: store.selectedPackages,
      step: 'done',
    });
    logLines.value.push('  ok');

    // Apply. Server writes .state.yml + .env, reports diff vs. running set.
    logLines.value.push('› Applying configuration…');
    const result = await OnboardingApi.install();
    for (const line of result.applied) logLines.value.push('  ' + line);
    store.installResult = result;

    // Deliberately no /complete call here. The backend refuses to launch
    // (or install, or patch) once onboarding.complete = true, and the Done
    // page still has to call POST /onboarding/launch after this. Committing
    // here would win the race against that call every time — see
    // dev/notes/onboarding-409-progress.md. OnboardingDone.vue commits once
    // the launch has actually succeeded (or there was nothing to launch),
    // so a failed launch leaves onboarding retryable instead of stranding
    // the user past the point of no return.

    // Small pause so the log renders before we navigate away.
    await new Promise((r) => setTimeout(r, 350));
    router.push('/onboarding/done');
  } catch (e) {
    installErr.value = classifyInstallError(e);
  } finally {
    installing.value = false;
  }
}

/**
 * Iter-3 §2a.ii: the install endpoint—on failure—returns a 500 with a
 * JSON body of shape {error, message}. Surface `message` verbatim as user
 * copy. Never leak stack traces. Falls back to a generic English line if
 * the body isn't the expected shape.
 */
function classifyInstallError(e: unknown): string {
  if (e && typeof e === 'object') {
    const anyE = e as Record<string, unknown>;
    // axios error: e.response.data holds the parsed JSON body.
    const response = anyE.response as Record<string, unknown> | undefined;
    const data = response?.data as Record<string, unknown> | undefined;
    if (data && typeof data.message === 'string' && data.message.length > 0) {
      if (typeof data.error === 'string') installErrCode.value = data.error;
      return data.message;
    }
    // Some clients stash the parsed body directly under `body`.
    const body = anyE.body as Record<string, unknown> | undefined;
    if (body && typeof body.message === 'string' && body.message.length > 0) {
      if (typeof body.error === 'string') installErrCode.value = body.error;
      return body.message;
    }
    if (typeof anyE.message === 'string') {
      const raw = anyE.message;
      // Try to parse `500: {"error":"...","message":"..."}` shape too.
      const idx = raw.indexOf('{');
      if (idx >= 0) {
        try {
          const parsed = JSON.parse(raw.slice(idx));
          if (parsed && typeof parsed.message === 'string' && parsed.message.length > 0) {
            if (typeof parsed.error === 'string') installErrCode.value = parsed.error;
            return parsed.message;
          }
        } catch { /* fall through */ }
      }
    }
  }
  // Anything not mapped to a human backend message routes through the
  // shared helper, so a raw axios string ("Request failed with status
  // code 500") never reaches the user.
  return humanCopyForError(e, { subject: 'the install', action: 'run' });
}

function retry(): void {
  installErr.value = null;
  installErrCode.value = null;
  void install();
}

function back(): void { store.back(); router.push(`/onboarding/${store.currentStep}`); }
</script>

<template>
  <div>
    <div class="eyebrow mb-3">Step 9 of 10</div>
    <h1 class="mb-4">Review and install.</h1>
    <p class="text-foreground mb-8">
      Here's what Aurora will do. Nothing has been written yet.
    </p>

    <Alert v-if="planErr" variant="warning" class="mb-6">
      <AlertDescription>{{ planErr }}</AlertDescription>
    </Alert>
    <div
      v-if="installErr"
      data-tone="err"
      role="alert"
      class="mb-6 flex items-start justify-between gap-4 px-4 py-3 rounded border border-destructive/25 bg-destructive/10 text-destructive text-sm"
    >
      <div class="flex-1">{{ installErr }}</div>
      <button
        type="button"
        class="shrink-0 text-sm px-3 py-1 rounded border border-destructive/35 hover:bg-destructive/10"
        @click="retry"
      >Retry</button>
    </div>

    <div class="border border-border rounded-lg divide-y divide-border mb-6">
      <div class="grid grid-cols-3 gap-4 px-5 py-4">
        <div class="text-muted-foreground text-sm">Domain</div>
        <div class="col-span-2 font-mono text-sm">{{ store.domain ?? '—' }}</div>
      </div>
      <div class="grid grid-cols-3 gap-4 px-5 py-4">
        <div class="text-muted-foreground text-sm">Admin</div>
        <div class="col-span-2 font-mono text-sm">
          {{ store.draft?.admin_username ?? store.admin?.username ?? '—' }}
        </div>
      </div>
      <div class="grid grid-cols-3 gap-4 px-5 py-4">
        <div class="text-muted-foreground text-sm">DNS</div>
        <div class="col-span-2 text-sm">{{ store.dnsMode ?? '—' }}</div>
      </div>
      <div class="grid grid-cols-3 gap-4 px-5 py-4">
        <div class="text-muted-foreground text-sm">Packages</div>
        <div class="col-span-2 flex flex-wrap gap-1.5">
          <span
            v-for="p in packagesToShow"
            :key="p"
            class="font-mono text-xs px-2 py-0.5 rounded border border-border bg-card"
          >{{ prettyPackageName(p) }}</span>
          <span v-if="packagesToShow.length === 0" class="text-muted-foreground text-sm">
            No packages selected.
          </span>
        </div>
      </div>
      <div class="grid grid-cols-3 gap-4 px-5 py-4">
        <div class="text-muted-foreground text-sm">vhosts</div>
        <div class="col-span-2 space-y-0.5">
          <div
            v-for="v in vhostsToShow"
            :key="v"
            class="font-mono text-xs text-foreground"
          >{{ v }}</div>
          <div v-if="vhostsToShow.length === 0" class="text-muted-foreground text-sm">
            Set the domain to preview vhosts.
          </div>
        </div>
      </div>
      <div class="grid grid-cols-3 gap-4 px-5 py-4">
        <div class="text-muted-foreground text-sm">Ports</div>
        <div class="col-span-2 font-mono text-xs">
          <span v-if="portsToShow.length > 0">{{ portsToShow.join(', ') }}</span>
          <span v-else class="text-muted-foreground text-sm font-sans">
            None — package manifests declare no host ports.
          </span>
        </div>
      </div>
    </div>

    <Alert
      v-for="(w, i) in warningsToShow"
      :key="i"
      variant="warning"
      class="mb-3"
    >
      <AlertDescription>{{ w }}</AlertDescription>
    </Alert>

    <div v-if="installing || logLines.length" class="border border-border rounded-lg p-4 mb-8 bg-foreground text-background font-mono text-xs max-h-64 overflow-auto" role="log" aria-live="polite">
      <div v-for="(l, i) in logLines" :key="i">{{ l }}</div>
      <div v-if="installing" class="text-muted-foreground/60">…</div>
    </div>

    <div class="mt-6 flex items-center justify-between">
      <Button variant="ghost" @click="back" :disabled="installing">Back</Button>
      <Button variant="accent" size="lg" @click="install" :loading="installing" data-cta="primary">
        Install
      </Button>
    </div>
  </div>
</template>
