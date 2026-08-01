<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useOnboardingStore } from '@/stores/onboarding';
import { useRouter } from 'vue-router';
import Button from '@/components/ui/Button.vue';
import LaunchProgress from '@/components/onboarding/LaunchProgress.vue';
import DoneChecklist from '@/components/onboarding/DoneChecklist.vue';
import { OnboardingApi } from '@/api/onboarding';

const store = useOnboardingStore();
const router = useRouter();

// Packages Aurora will bring up. `packages_to_start` is populated by the
// install() call on Review; if the user reloaded, fall back to the store's
// selection so the CTA still makes sense.
const toStart = computed<string[]>(() =>
  store.installResult?.packages_to_start ?? store.selectedPackages ?? [],
);

const launchJobId = ref<string | null>(null);
const launchState = ref<'idle' | 'running' | 'success' | 'failed'>('idle');
const launchError = ref<string | null>(null);
const starting = ref(false);

// P2 #5: persist the running job across a page reload. Session-scoped so a
// closed tab doesn't leak a stale id into a new onboarding attempt.
const STORAGE_KEY = 'aurora.launch.currentJob';
const REHYDRATE_MAX_AGE_MS = 30 * 60 * 1000; // 30 min

interface StoredJob { jobId: string; startedAt: number }

function readStoredJob(): StoredJob | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as StoredJob;
    if (!parsed?.jobId || typeof parsed.startedAt !== 'number') return null;
    if (Date.now() - parsed.startedAt > REHYDRATE_MAX_AGE_MS) return null;
    return parsed;
  } catch { return null; }
}

function writeStoredJob(job: StoredJob | null): void {
  try {
    if (job) sessionStorage.setItem(STORAGE_KEY, JSON.stringify(job));
    else sessionStorage.removeItem(STORAGE_KEY);
  } catch { /* private-mode etc; not fatal */ }
}

const canGoToDashboard = computed(() => {
  // No packages to start? User can leave immediately.
  if (toStart.value.length === 0) return true;
  return launchState.value === 'success';
});

async function startServices(): Promise<void> {
  starting.value = true;
  launchError.value = null;
  try {
    const res = await OnboardingApi.startLaunch();
    launchJobId.value = res.job_id;
    launchState.value = 'running';
    writeStoredJob({ jobId: res.job_id, startedAt: Date.now() });
  } catch (e: unknown) {
    launchError.value = extractError(e) ?? 'Could not start the launch.';
  } finally {
    starting.value = false;
  }
}

function onLaunchSuccess(): void {
  launchState.value = 'success';
  writeStoredJob(null);
}

function onLaunchFailed(reason: string): void {
  launchState.value = 'failed';
  launchError.value = reason;
  writeStoredJob(null);
}

async function onLaunchRetry(): Promise<void> {
  launchJobId.value = null;
  writeStoredJob(null);
  await startServices();
}

function extractError(e: unknown): string | null {
  if (typeof e === 'object' && e !== null && 'message' in e) {
    return String((e as { message: unknown }).message);
  }
  return null;
}

function toDashboard(): void {
  store.clearAllDrafts();
  writeStoredJob(null);
  router.push('/');
}

// P2 #5: on mount, if we have a stored jobId, ask the backend what state
// it's in and rehydrate the UI accordingly. Handles reload-mid-launch.
onMounted(async () => {
  const stored = readStoredJob();
  if (!stored) return;
  try {
    const snapshot = await OnboardingApi.getLaunchStatus(stored.jobId);
    if (snapshot.state === 'running') {
      launchJobId.value = stored.jobId;
      launchState.value = 'running';
    } else if (snapshot.state === 'success') {
      launchState.value = 'success';
      writeStoredJob(null);
    } else {
      // failed or unknown — surface the reason if we have one, then let the
      // user retry.
      launchState.value = 'failed';
      launchError.value = snapshot.failure_reason
        ?? 'The previous launch attempt did not finish. Try again.';
      writeStoredJob(null);
    }
  } catch {
    // Backend restarted / job forgotten — clear and render fresh CTA.
    writeStoredJob(null);
  }
});
</script>

<template>
  <div>
    <div class="eyebrow mb-3">Step 9 of 9</div>
    <h1 class="mb-4">You're set.</h1>
    <p class="text-ink-2 mb-8">
      Aurora is configured. Bring your services online, then head to the dashboard.
    </p>

    <!-- Bring your services online -->
    <div
      v-if="toStart.length > 0 && launchState === 'idle'"
      class="border border-line rounded-lg p-5 mb-6 bg-surface-2/60"
      data-testid="launch-cta"
    >
      <div class="eyebrow mb-2" style="color: var(--color-accent)">Almost there</div>
      <h3 class="mb-2">Bring your services online</h3>
      <p class="text-sm text-ink-2 mb-4">
        Aurora will start
        <span
          v-for="(p, i) in toStart"
          :key="p"
          class="font-mono text-xs px-1.5 py-0.5 rounded border border-line bg-surface ml-1"
        >{{ p }}<template v-if="i < toStart.length - 1">&nbsp;</template></span>
        for you. No typing required.
      </p>
      <div class="flex items-center gap-3">
        <Button
          variant="primary"
          size="lg"
          :loading="starting"
          :disabled="starting"
          data-testid="start-services"
          data-cta="primary"
          @click="startServices"
        >Start services</Button>
        <span v-if="launchError" class="text-sm text-red-700" data-testid="launch-error">{{ launchError }}</span>
      </div>
    </div>

    <div v-if="launchJobId && launchState !== 'idle'" class="mb-6">
      <LaunchProgress
        :job-id="launchJobId"
        :packages="toStart"
        @success="onLaunchSuccess"
        @failed="onLaunchFailed"
        @retry="onLaunchRetry"
      />
    </div>

    <!-- Follow-up living checklist (iter-2). Replaces the four static tiles. -->
    <DoneChecklist
      v-if="launchState !== 'running'"
      :enabled-packages="toStart"
      class="mb-10"
    />

    <div class="flex items-center justify-between border-t border-line pt-6">
      <div class="text-sm text-ink-3">
        <div class="eyebrow mb-1">Reminder</div>
        One box, one URL, one dashboard. That's the whole point.
      </div>
      <Button
        variant="primary"
        size="lg"
        :disabled="!canGoToDashboard"
        data-testid="to-dashboard"
        @click="toDashboard"
      >Go to my dashboard</Button>
    </div>
  </div>
</template>
