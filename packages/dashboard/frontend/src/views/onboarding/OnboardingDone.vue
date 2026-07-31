<script setup lang="ts">
import { useOnboardingStore } from '@/stores/onboarding';
import { useRouter } from 'vue-router';
import Button from '@/components/ui/Button.vue';
import Card from '@/components/ui/Card.vue';
import { OnboardingApi } from '@/api/onboarding';

const store = useOnboardingStore();
const router = useRouter();

async function toDashboard(): Promise<void> {
  try { await OnboardingApi.complete(); } catch { /* soft */ }
  router.push('/');
}
</script>

<template>
  <div>
    <div class="eyebrow mb-3">Step 9 of 9</div>
    <h1 class="mb-4">You're up.</h1>
    <p class="text-ink-2 mb-10">
      Your box is provisioned. Below is what to do next — the setup wizards for the
      services you enabled, ordered by "you'll want this first".
    </p>

    <div class="grid grid-cols-2 gap-4 mb-10">
      <Card hover>
        <div class="eyebrow mb-1">Home</div>
        <h3 class="mb-2">Aurora</h3>
        <p class="text-sm text-ink-3 mb-4">
          The dashboard you're standing in. Manage packages, secrets, health, and
          security posture from here. Bookmark <code class="text-ink">{{ store.domain }}</code>.
        </p>
        <a :href="`http://${store.domain}`" class="text-sm text-ink no-underline">Open Aurora →</a>
      </Card>

      <Card v-if="store.selectedPackages.includes('privacy')" hover>
        <div class="eyebrow mb-1">Next</div>
        <h3 class="mb-2">AdGuard first-run</h3>
        <p class="text-sm text-ink-3 mb-4">
          Set the AdGuard admin password. Aurora can't do this for you — AdGuard's
          initial setup is client-side.
        </p>
        <a :href="`http://${store.domain}:3000/`" class="text-sm text-ink no-underline">Open AdGuard wizard →</a>
      </Card>

      <Card v-if="store.selectedPackages.includes('media')" hover>
        <div class="eyebrow mb-1">Media</div>
        <h3 class="mb-2">Onboard Sonarr / Radarr / Seerr</h3>
        <p class="text-sm text-ink-3 mb-4">
          Each *arr wants an admin user and one indexer + one download client
          configured. Prowlarr wires the indexers automatically.
        </p>
        <a :href="`http://prowlarr.${store.domain}`" class="text-sm text-ink no-underline">Start with Prowlarr →</a>
      </Card>

      <Card v-if="store.selectedPackages.includes('storage')" hover>
        <div class="eyebrow mb-1">Files</div>
        <h3 class="mb-2">Mount the SMB share</h3>
        <p class="text-sm text-ink-3 mb-4">
          Samba is up on the LAN. On macOS: <kbd>⌘K</kbd> then
          <code class="text-ink">smb://{{ store.domain }}</code>. On Windows: File Explorer →
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
