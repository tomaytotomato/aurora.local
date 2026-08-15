<script setup lang="ts">
import { ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useOnboardingStore } from '@/stores/onboarding';
import { MANDATORY_FIRST_RUN_PACKAGES } from '@/api/packages';
import Button from '@/components/ui/Button.vue';
import Input from '@/components/ui/Input.vue';
import Label from '@/components/ui/Label.vue';
import { Alert, AlertDescription } from '@/components/ui';

const store = useOnboardingStore();
const router = useRouter();

// Prefill from the (already hydrated) store. Watch the store in case
// hydration completes after this view mounts.
const domain = ref(store.domain);
watch(() => store.domain, (v) => { if (v && v !== domain.value) domain.value = v; });
const err = ref<string | null>(null);

const domainOk = (d: string): boolean => /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/i.test(d);

async function proceed(): Promise<void> {
  err.value = null;
  if (!domainOk(domain.value)) {
    err.value = 'That doesn\'t look like a valid domain.';
    return;
  }
  // Seed .state.yml's enabled[] with the mandatory baseline here — this is
  // the job the interactive package-picker step used to do (it PATCHed
  // enabled_packages right before handing off to the SSO step). `identity`
  // is deliberately left out: the very next step (SSO) decides whether
  // Authelia is enabled, generates its secrets, and neutralises other
  // packages' internal auth — none of that should be pre-empted here.
  // OnboardingService#install() force-adds the same baseline again later
  // as a belt-and-braces safety net if this step gets skipped via the
  // sidebar.
  await store.patchDraft({
    domain: domain.value,
    enabled_packages: [...MANDATORY_FIRST_RUN_PACKAGES],
    step: 'sso',
  });
  store.next();
  router.push(`/onboarding/${store.currentStep}`);
}
</script>

<template>
  <div>
    <div class="eyebrow mb-3">{{ store.stepEyebrow }}</div>
    <h1 class="mb-4">Pick your domain.</h1>
    <p class="text-foreground mb-8">
      Aurora and every package it manages live under one domain. The default,
      <code class="bg-muted px-1 py-0.5 rounded border border-border">aurora.local</code>,
      resolves over mDNS on your LAN and needs no external DNS.
    </p>

    <Alert v-if="err" variant="destructive" class="mb-6">
      <AlertDescription>{{ err }}</AlertDescription>
    </Alert>

    <div class="mb-8">
      <Label for="domain">Domain</Label>
      <Input id="domain" v-model="domain" autocomplete="off" class="font-mono" />
      <p class="text-xs text-muted-foreground mt-2">
        Services will appear at <code>&lt;name&gt;.{{ domain }}</code>. The admin panel
        (this) lives at <code class="text-foreground">admin.{{ domain }}</code>.
      </p>
    </div>

    <div class="border border-border rounded-lg p-5 mb-8 bg-muted/50">
      <div class="eyebrow mb-2">What changes if you edit this</div>
      <ul class="text-sm text-muted-foreground space-y-1.5">
        <li>Caddy vhosts are re-issued for the new apex.</li>
        <li>Every <code>.env</code> that references <code>${'{'}DOMAIN{'}'}</code> re-renders.</li>
        <li>AdGuard DNS rewrites are updated.</li>
        <li>You'll need to trust the new TLS root (step 7).</li>
      </ul>
    </div>

    <div class="flex items-center justify-between">
      <Button variant="ghost" @click="() => { store.back(); router.push(`/onboarding/${store.currentStep}`); }">
        Back
      </Button>
      <Button variant="primary" size="lg" @click="proceed">Continue</Button>
    </div>
  </div>
</template>
