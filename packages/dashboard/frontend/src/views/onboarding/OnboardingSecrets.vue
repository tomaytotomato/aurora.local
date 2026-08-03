<script setup lang="ts">
import { useRouter } from 'vue-router';
import { useOnboardingStore } from '@/stores/onboarding';
import Button from '@/components/ui/Button.vue';
import { Alert, AlertTitle, AlertDescription } from '@/components/ui';

const store = useOnboardingStore();
const router = useRouter();

function next(): void { store.next(); router.push(`/onboarding/${store.currentStep}`); }
function back(): void { store.back(); router.push(`/onboarding/${store.currentStep}`); }
</script>

<template>
  <div>
    <div class="eyebrow mb-3">Step 5 of 9</div>
    <h1 class="mb-4">Configure secrets.</h1>
    <p class="text-ink-2 mb-8">
      Each selected package has an <code class="bg-surface-2 px-1 py-0.5 rounded border border-line">.env</code>
      file. Aurora renders it as a form and generates strong values for anything that looks
      like a secret.
    </p>

    <Alert variant="info" class="mb-8">
      <AlertTitle>Landing in the next slice</AlertTitle>
      <AlertDescription>
        The per-package secrets form ships with M2. For v0.1, Aurora uses each package's
        <code>.env.example</code> as-is and auto-generates any missing secrets during
        install. You can edit them from
        <em>Packages → &lt;name&gt; → Config</em> after onboarding.
      </AlertDescription>
    </Alert>

    <div class="border border-line rounded-lg p-6 mb-10 bg-surface-2/40">
      <div class="eyebrow mb-2">Selected packages</div>
      <div class="flex flex-wrap gap-2">
        <span
          v-for="p in store.selectedPackages"
          :key="p"
          class="font-mono text-xs px-2 py-1 rounded border border-line bg-surface"
        >{{ p }}</span>
      </div>
    </div>

    <div class="flex items-center justify-between">
      <Button variant="ghost" @click="back">Back</Button>
      <Button variant="primary" size="lg" @click="next">Continue</Button>
    </div>
  </div>
</template>
