<script setup lang="ts">
import { computed, ref } from 'vue';
import { useOnboardingStore } from '@/stores/onboarding';
import { useRouter } from 'vue-router';
import Button from '@/components/ui/Button.vue';
import Card from '@/components/ui/Card.vue';
import LaunchProgress from '@/components/onboarding/LaunchProgress.vue';
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
  } catch (e: unknown) {
    launchError.value = extractError(e) ?? 'Could not start the launch.';
  } finally {
    starting.value = false;
  }
}

function onLaunchSuccess(): void {
  launchState.value = 'success';
}

function onLaunchFailed(reason: string): void {
  launchState.value = 'failed';
  launchError.value = reason;
}

async function onLaunchRetry(): Promise<void> {
  launchJobId.value = null;
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
  router.push('/');
}
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

    <!-- Follow-up tiles: unchanged in iter-1. iter-2 owns the checklist grid. -->
    <div class="grid grid-cols-2 gap-4 mb-10" v-if="launchState !== 'running'">
      <Card hover>
        <div class="eyebrow mb-1">Home</div>
        <h3 class="mb-2">Aurora</h3>
        <p class="text-sm text-ink-3 mb-4">
          The dashboard you're standing in. Manage packages, secrets, health, and
          security posture from here. Bookmark <code class="text-ink">{{ store.domain }}</code>.
        </p>
        <a :href="`http://${store.domain}`" class="text-sm text-ink no-underline">Open Aurora &rarr;</a>
      </Card>

      <Card v-if="store.selectedPackages.includes('privacy')" hover>
        <div class="eyebrow mb-1">Next</div>
        <h3 class="mb-2">AdGuard first-run</h3>
        <p class="text-sm text-ink-3 mb-4">
          Set the AdGuard admin password. Aurora can't do this for you &mdash; AdGuard's
          initial setup is client-side.
        </p>
        <a :href="`http://${store.domain}:3000/`" class="text-sm text-ink no-underline">Open AdGuard wizard &rarr;</a>
      </Card>

      <Card v-if="store.selectedPackages.includes('media')" hover>
        <div class="eyebrow mb-1">Media</div>
        <h3 class="mb-2">Onboard Sonarr / Radarr / Seerr</h3>
        <p class="text-sm text-ink-3 mb-4">
          Each *arr wants an admin user and one indexer + one download client
          configured. Prowlarr wires the indexers automatically.
        </p>
        <a :href="`http://prowlarr.${store.domain}`" class="text-sm text-ink no-underline">Start with Prowlarr &rarr;</a>
      </Card>

      <Card v-if="store.selectedPackages.includes('storage')" hover>
        <div class="eyebrow mb-1">Files</div>
        <h3 class="mb-2">Mount the shared folder</h3>
        <p class="text-sm text-ink-3 mb-4">
          Files is up on the LAN. On macOS: use Connect to Server with
          <code class="text-ink">{{ store.domain }}</code>. On Windows: open File Explorer and
          browse to <code class="text-ink">{{ store.domain }}</code>.
        </p>
      </Card>
    </div>

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
      >Take me to Aurora</Button>
    </div>
  </div>
</template>
