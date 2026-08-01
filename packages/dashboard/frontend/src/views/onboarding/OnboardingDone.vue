<script setup lang="ts">
import { computed } from 'vue';
import { useOnboardingStore } from '@/stores/onboarding';
import { useRouter } from 'vue-router';
import Button from '@/components/ui/Button.vue';
import Card from '@/components/ui/Card.vue';
import Alert from '@/components/ui/Alert.vue';

const store = useOnboardingStore();
const router = useRouter();

const toStart = computed(() => store.installResult?.packages_to_start ?? []);
const toStop = computed(() => store.installResult?.packages_to_stop ?? []);
const hostCmd = computed(
  () => store.installResult?.host_command ?? 'cd ~/aurora.local && ./scripts/up.sh',
);

function toDashboard(): void {
  // Onboarding is already committed by Review's install() flow. Nothing to
  // POST here \u2014 just clean up local drafts and hand off.
  store.clearAllDrafts();
  router.push('/');
}
</script>

<template>
  <div>
    <div class="eyebrow mb-3">Step 9 of 9</div>
    <h1 class="mb-4">You're up.</h1>
    <p class="text-ink-2 mb-8">
      Your onboarding is committed. Below is what to do next.
    </p>

    <!-- Host action required: some enabled packages aren't running yet.
         Aurora can't spawn containers itself (no docker CLI in this image),
         so tell the operator exactly what to type. -->
    <div v-if="toStart.length > 0" class="border border-line rounded-lg p-5 mb-6 bg-surface-2/60">
      <div class="eyebrow mb-2" style="color: var(--color-accent)">Action required on the host</div>
      <p class="text-sm text-ink-2 mb-3">
        These packages are enabled but not running yet. SSH into the box and run:
      </p>
      <pre class="bg-[var(--color-ink)] text-[var(--color-canvas)] font-mono text-xs px-4 py-3 rounded overflow-auto mb-3">{{ hostCmd }}</pre>
      <div class="flex flex-wrap gap-1.5">
        <span
          v-for="p in toStart"
          :key="p"
          class="font-mono text-xs px-2 py-0.5 rounded border border-line bg-surface"
        >{{ p }}</span>
      </div>
    </div>

    <!-- Informational: containers running for packages you deselected. v0.1
         doesn't stop them automatically, so surface it. -->
    <Alert v-if="toStop.length > 0" tone="info" class="mb-6">
      Deselected packages have containers still running:
      <span
        v-for="p in toStop"
        :key="p"
        class="font-mono text-xs ml-1 px-1.5 py-0.5 rounded border border-line bg-surface"
      >{{ p }}</span>
      &mdash; run <code>./scripts/down.sh &lt;pkg&gt;</code> on the host to stop them.
    </Alert>

    <div class="grid grid-cols-2 gap-4 mb-10">
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
        <h3 class="mb-2">Mount the SMB share</h3>
        <p class="text-sm text-ink-3 mb-4">
          Samba is up on the LAN. On macOS: <kbd>&#8984;K</kbd> then
          <code class="text-ink">smb://{{ store.domain }}</code>. On Windows: File Explorer &rarr;
          <code class="text-ink">\\{{ store.domain }}</code>.
        </p>
      </Card>
    </div>

    <div class="flex items-center justify-between border-t border-line pt-6">
      <div class="text-sm text-ink-3">
        <div class="eyebrow mb-1">Reminder</div>
        One box, one URL, one dashboard. That's the whole point.
      </div>
      <Button variant="primary" size="lg" @click="toDashboard">Take me to Aurora</Button>
    </div>
  </div>
</template>
