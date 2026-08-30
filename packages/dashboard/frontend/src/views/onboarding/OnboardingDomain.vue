<script setup lang="ts">
import { ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useOnboardingStore } from '@/stores/onboarding';
import { STEP_LABELS } from '@/stores/onboarding';
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

// Name the step, never its number. This line used to read "(step 7)" while
// the TLS step was 5 and 7 was SSO — a hardcoded index that went stale the
// moment the step list changed, on a card whose whole job is telling the
// user what happens next.
const tlsStepLabel = STEP_LABELS.tls;

const domainOk = (d: string): boolean => /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/i.test(d);

async function proceed(): Promise<void> {
  err.value = null;
  if (!domainOk(domain.value)) {
    err.value = 'That doesn\'t look like a valid domain.';
    return;
  }
  // Seed .state.yml's enabled[] with the mandatory baseline here — this is
  // the job the interactive package-picker step used to do. The baseline
  // is just `core` now (D5): SSO/Authelia ships inside core and is
  // always-on, and storage + everything else is a day-2 catalogue install,
  // so nothing extra is pre-empted here. OnboardingService#install()
  // force-adds the same baseline again later as a belt-and-braces safety
  // net if this step gets skipped via the sidebar.
  //
  // Union with whatever the server already reports, not a wholesale
  // replace: the host's own bootstrap (bootstrap.sh / scripts/up.sh)
  // brings `core` up before the wizard ever runs, so `draft
  // .enabled_packages` can already be non-empty by the time this step
  // submits (e.g. a previous partial attempt already added something).
  //
  // `dashboard` is filtered out rather than preserved: the backend's
  // PackageNameValidator permanently REJECTS it in any enabled_packages
  // payload (it's in that validator's own RESERVED set) with a 400,
  // because it is never meant to be client-submitted at all — it is the
  // "always present" infrastructure package this very page is served
  // by, tracked outside `.state.yml`'s enabled[] entirely (see
  // PackagesService.INFRASTRUCTURE_PACKAGES). Bootstrap seeds
  // `enabled: [core, dashboard]` directly (not through this endpoint),
  // which is exactly why this filter is needed: without it, the very
  // first real PATCH this step ever sends would 400 on every real box.
  const baseline = new Set([
    ...(store.draft?.enabled_packages ?? []).filter((p) => p !== 'dashboard'),
    ...MANDATORY_FIRST_RUN_PACKAGES,
  ]);
  await store.patchDraft({
    domain: domain.value,
    enabled_packages: [...baseline],
    step: 'dns',
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
        <li>Every app moves to a new address, like <code>photos.{{ domain }}</code>.</li>
        <li>Aurora re-issues the certificates that keep those addresses private.</li>
        <li>DNS on this box starts pointing the new name at it.</li>
        <li>You'll need to trust the new certificate, on the <em>{{ tlsStepLabel }}</em> step.</li>
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
