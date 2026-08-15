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
    <div class="eyebrow mb-3">{{ store.stepEyebrow }}</div>
    <h1 class="mb-4">Configure secrets.</h1>
    <p class="text-foreground mb-8">
      Some apps need secrets like passwords or API keys. Aurora fills in strong
      values for you, so there's nothing to type here.
    </p>

    <Alert variant="info" class="mb-8">
      <AlertTitle>Secrets are handled for you</AlertTitle>
      <AlertDescription>
        Aurora generates a strong value for anything that looks like a secret
        when it installs each app. You can review or change any of them from an
        app's Config screen afterwards.
      </AlertDescription>
    </Alert>

    <div class="border border-border rounded-lg p-6 mb-10 bg-muted/40">
      <div class="eyebrow mb-2">Selected packages</div>
      <div class="flex flex-wrap gap-2">
        <span
          v-for="p in (store.draft?.enabled_packages ?? [])"
          :key="p"
          class="font-mono text-xs px-2 py-1 rounded border border-border bg-card"
        >{{ p }}</span>
      </div>
    </div>

    <div class="flex items-center justify-between">
      <Button variant="ghost" @click="back">Back</Button>
      <Button variant="primary" size="lg" @click="next">Continue</Button>
    </div>
  </div>
</template>
