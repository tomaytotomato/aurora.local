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
        <div class="eyebrow mb-1">First</div>
        <h3 class="mb-2">Homepage</h3>
        <p class="text-sm text-ink-3 mb-4">
          The tile grid your household uses day-to-day. Everything you just installed
          appears there.
        </p>
        <a :href="`http://${store.domain}`" class="text-sm text-ink no-underline">Open Homepage →</a>
      </Card>

      <Card hover>
        <div class="eyebrow mb-1">Admin plane</div>
        <h3 class="mb-2">Aurora</h3>
        <p class="text-sm text-ink-3 mb-4">
          This — where you manage packages, secrets, health, and security posture.
          Bookmark it.
        </p>
        <a :href="`http://admin.${store.domain}`" class="text-sm text-ink no-underline">Open Aurora →</a>
      </Card>

      <Card v-if="store.selectedPackages.includes('privacy')">
        <div class="eyebrow mb-1">Next</div>
        <h3 class="mb-2">AdGuard first-run</h3>
        <p class="text-sm text-ink-3 mb-4">
          Set the AdGuard admin password. Aurora can't do this for you — AdGuard's
          initial setup is client-side.
        </p>
        <a :href="`http://${store.domain}:3000/`" class="text-sm text-ink no-underline">Open AdGuard wizard →</a>
      </Card>

      <Card v-if="store.selectedPackages.includes('media')">
        <div class="eyebrow mb-1">Media</div>
        <h3 class="mb-2">Onboard Sonarr / Radarr / Seerr</h3>
        <p class="text-sm text-ink-3 mb-4">
          Each *arr wants an admin user and one indexer + one download client
          configured. Prowlarr wires the indexers automatically.
        </p>
        <a :href="`http://prowlarr.${store.domain}`" class="text-sm text-ink no-underline">Start with Prowlarr →</a>
      </Card>
    </div>

    <div class="flex items-center justify-between border-t border-line pt-6">
      <div class="text-sm text-ink-3">
        <div class="eyebrow mb-1">Reminder</div>
        Homepage is the tile grid. Aurora is the fuse box.
      </div>
      <Button variant="primary" size="lg" @click="toDashboard">Take me to Aurora</Button>
    </div>
  </div>
</template>
