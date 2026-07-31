<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useOnboardingStore } from '@/stores/onboarding';
import { useEventsStore } from '@/stores/events';
import { OnboardingApi, type InstallPlan } from '@/api/onboarding';
import Button from '@/components/ui/Button.vue';
import Alert from '@/components/ui/Alert.vue';

const store = useOnboardingStore();
const events = useEventsStore();
const router = useRouter();

const plan = ref<InstallPlan | null>(null);
const planErr = ref<string | null>(null);
const installing = ref(false);
const installErr = ref<string | null>(null);
const jobId = ref<string | null>(null);
const logLines = ref<string[]>([]);

onMounted(async () => {
  try {
    plan.value = await OnboardingApi.plan();
  } catch (e) {
    // Local fallback plan if backend not up yet
    plan.value = {
      packagesToEnable: store.selectedPackages,
      packagesToDisable: [],
      vhosts: store.selectedPackages.map((p) => `${p}.${store.domain}`),
      ports: [80, 443, 53, 3000],
      warnings: [],
    };
    planErr.value = e instanceof Error ? `Preview only — backend unreachable (${e.message})` : 'Preview only';
  }
});

async function install(): Promise<void> {
  installing.value = true;
  installErr.value = null;
  logLines.value = [];
  try {
    events.connect();
    const { jobId: jid } = await OnboardingApi.install();
    jobId.value = jid;
    // Watch events for our job.
    const off = setInterval(() => {
      const forJob = events.buffer.filter((e) => e.kind === 'job' && e.jobId === jid);
      logLines.value = forJob.flatMap((e) => (e.kind === 'job' && e.message ? [e.message] : []));
      const last = forJob[forJob.length - 1];
      if (last?.kind === 'job' && (last.phase === 'done' || last.phase === 'failed')) {
        clearInterval(off);
        installing.value = false;
        if (last.phase === 'done') {
          store.next();
          router.push('/onboarding/done');
        } else {
          installErr.value = last.message ?? 'Install failed';
        }
      }
    }, 400);
  } catch (e) {
    installErr.value = e instanceof Error ? e.message : 'Install failed';
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

    <div v-if="plan" class="border border-line rounded-lg divide-y divide-[var(--color-line-2)] mb-10">
      <div class="grid grid-cols-3 gap-4 px-5 py-4">
        <div class="text-ink-3 text-sm">Domain</div>
        <div class="col-span-2 font-mono text-sm">{{ store.domain }}</div>
      </div>
      <div class="grid grid-cols-3 gap-4 px-5 py-4">
        <div class="text-ink-3 text-sm">Admin</div>
        <div class="col-span-2 font-mono text-sm">{{ store.admin?.username ?? '—' }}</div>
      </div>
      <div class="grid grid-cols-3 gap-4 px-5 py-4">
        <div class="text-ink-3 text-sm">DNS</div>
        <div class="col-span-2 text-sm">{{ store.dnsMode ?? '—' }}</div>
      </div>
      <div class="grid grid-cols-3 gap-4 px-5 py-4">
        <div class="text-ink-3 text-sm">Packages</div>
        <div class="col-span-2 flex flex-wrap gap-1.5">
          <span v-for="p in plan.packagesToEnable" :key="p" class="font-mono text-xs px-2 py-0.5 rounded border border-line bg-surface">{{ p }}</span>
        </div>
      </div>
      <div class="grid grid-cols-3 gap-4 px-5 py-4">
        <div class="text-ink-3 text-sm">vhosts</div>
        <div class="col-span-2 space-y-0.5">
          <div v-for="v in plan.vhosts" :key="v" class="font-mono text-xs text-ink-2">{{ v }}</div>
        </div>
      </div>
      <div class="grid grid-cols-3 gap-4 px-5 py-4">
        <div class="text-ink-3 text-sm">Ports</div>
        <div class="col-span-2 font-mono text-xs">{{ plan.ports.join(', ') }}</div>
      </div>
    </div>

    <div v-if="installing || logLines.length" class="border border-line rounded-lg p-4 mb-8 bg-[var(--color-ink)] text-[var(--color-canvas)] font-mono text-xs max-h-64 overflow-auto">
      <div v-for="(l, i) in logLines" :key="i">{{ l }}</div>
      <div v-if="installing" class="text-ink-4/60">…</div>
    </div>

    <div class="flex items-center justify-between">
      <Button variant="ghost" @click="back" :disabled="installing">Back</Button>
      <Button variant="accent" size="lg" @click="install" :loading="installing">
        Install
      </Button>
    </div>
  </div>
</template>
