<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useOnboardingStore } from '@/stores/onboarding';
import { OnboardingApi, type InstallPlan } from '@/api/onboarding';
import Button from '@/components/ui/Button.vue';
import Alert from '@/components/ui/Alert.vue';

const store = useOnboardingStore();
const router = useRouter();

const plan = ref<InstallPlan | null>(null);
const planErr = ref<string | null>(null);
const installing = ref(false);
const installErr = ref<string | null>(null);
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
    planErr.value = e instanceof Error
      ? `Backend unreachable (${e.message}) — showing local preview only`
      : 'Backend unreachable — showing local preview only';
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
  logLines.value = [];
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

    // Commit. This flips onboarding.complete = true.
    logLines.value.push('› Committing onboarding…');
    await OnboardingApi.complete();
    logLines.value.push('  ok');

    // Small pause so the log renders before we navigate away.
    await new Promise((r) => setTimeout(r, 350));
    router.push('/onboarding/done');
  } catch (e) {
    installErr.value = e instanceof Error ? e.message : 'Install failed';
  } finally {
    installing.value = false;
  }
}

function back(): void { store.back(); router.push(`/onboarding/${store.currentStep}`); }
</script>

<template>
  <div>
    <div class="eyebrow mb-3">Step 8 of 9</div>
    <h1 class="mb-4">Review and install.</h1>
    <p class="text-ink-2 mb-8">
      Here's what Aurora will do. Nothing has been written yet.
    </p>

    <Alert v-if="planErr" tone="warn" class="mb-6">{{ planErr }}</Alert>
    <Alert v-if="installErr" tone="err" class="mb-6">{{ installErr }}</Alert>

    <div class="border border-line rounded-lg divide-y divide-[var(--color-line-2)] mb-6">
      <div class="grid grid-cols-3 gap-4 px-5 py-4">
        <div class="text-ink-3 text-sm">Domain</div>
        <div class="col-span-2 font-mono text-sm">{{ store.domain ?? '—' }}</div>
      </div>
      <div class="grid grid-cols-3 gap-4 px-5 py-4">
        <div class="text-ink-3 text-sm">Admin</div>
        <div class="col-span-2 font-mono text-sm">
          {{ store.draft?.admin_username ?? store.admin?.username ?? '—' }}
        </div>
      </div>
      <div class="grid grid-cols-3 gap-4 px-5 py-4">
        <div class="text-ink-3 text-sm">DNS</div>
        <div class="col-span-2 text-sm">{{ store.dnsMode ?? '—' }}</div>
      </div>
      <div class="grid grid-cols-3 gap-4 px-5 py-4">
        <div class="text-ink-3 text-sm">Packages</div>
        <div class="col-span-2 flex flex-wrap gap-1.5">
          <span
            v-for="p in packagesToShow"
            :key="p"
            class="font-mono text-xs px-2 py-0.5 rounded border border-line bg-surface"
          >{{ p }}</span>
          <span v-if="packagesToShow.length === 0" class="text-ink-4 text-sm">
            No packages selected.
          </span>
        </div>
      </div>
      <div class="grid grid-cols-3 gap-4 px-5 py-4">
        <div class="text-ink-3 text-sm">vhosts</div>
        <div class="col-span-2 space-y-0.5">
          <div
            v-for="v in vhostsToShow"
            :key="v"
            class="font-mono text-xs text-ink-2"
          >{{ v }}</div>
          <div v-if="vhostsToShow.length === 0" class="text-ink-4 text-sm">
            Set the domain to preview vhosts.
          </div>
        </div>
      </div>
      <div class="grid grid-cols-3 gap-4 px-5 py-4">
        <div class="text-ink-3 text-sm">Ports</div>
        <div class="col-span-2 font-mono text-xs">
          <span v-if="portsToShow.length > 0">{{ portsToShow.join(', ') }}</span>
          <span v-else class="text-ink-4 text-sm font-sans">
            None — package manifests declare no host ports.
          </span>
        </div>
      </div>
    </div>

    <Alert
      v-for="(w, i) in warningsToShow"
      :key="i"
      tone="warn"
      class="mb-3"
    >{{ w }}</Alert>

    <div v-if="installing || logLines.length" class="border border-line rounded-lg p-4 mb-8 bg-[var(--color-ink)] text-[var(--color-canvas)] font-mono text-xs max-h-64 overflow-auto">
      <div v-for="(l, i) in logLines" :key="i">{{ l }}</div>
      <div v-if="installing" class="text-ink-4/60">…</div>
    </div>

    <div class="mt-6 flex items-center justify-between">
      <Button variant="ghost" @click="back" :disabled="installing">Back</Button>
      <Button variant="accent" size="lg" @click="install" :loading="installing">
        Install
      </Button>
    </div>
  </div>
</template>
