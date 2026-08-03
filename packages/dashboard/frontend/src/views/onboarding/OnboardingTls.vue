<script setup lang="ts">
import { useRouter } from 'vue-router';
import { useOnboardingStore } from '@/stores/onboarding';
import { OnboardingApi } from '@/api/onboarding';
import Button from '@/components/ui/Button.vue';
import { Alert, AlertDescription } from '@/components/ui';

const store = useOnboardingStore();
const router = useRouter();

function next(): void { store.next(); router.push(`/onboarding/${store.currentStep}`); }
function back(): void { store.back(); router.push(`/onboarding/${store.currentStep}`); }
</script>

<template>
  <div>
    <div class="eyebrow mb-3">Step 8 of 10</div>
    <h1 class="mb-4">Trust the TLS root.</h1>
    <p class="text-foreground mb-8">
      Caddy on this box issues its own TLS certificates for
      <code class="bg-muted px-1 py-0.5 rounded border border-border">*.{{ store.domain }}</code>.
      For your browser and OS to stop warning, install this box's root CA.
    </p>

    <div class="border border-border rounded-lg p-6 mb-6 bg-muted/40">
      <div class="eyebrow mb-2">Root CA</div>
      <div class="flex items-center justify-between gap-4">
        <div class="font-mono text-sm text-foreground">caddy-root.crt</div>
        <a :href="OnboardingApi.caddyRootCaUrl()" download>
          <Button variant="secondary" size="sm">Download</Button>
        </a>
      </div>
    </div>

    <div class="space-y-4 mb-10 text-sm text-muted-foreground">
      <div>
        <div class="eyebrow mb-1 text-foreground">macOS</div>
        <p>Double-click the file, add to <em>System</em> keychain, then set to
          <em>Always Trust</em> in the certificate's info panel.</p>
      </div>
      <div>
        <div class="eyebrow mb-1 text-foreground">Windows</div>
        <p>Right-click → Install Certificate → Local Machine → place in
          <em>Trusted Root Certification Authorities</em>.</p>
      </div>
      <div>
        <div class="eyebrow mb-1 text-foreground">Linux (Debian/Ubuntu)</div>
        <p>Save the file to your Downloads folder. Aurora will show you a
          step-by-step in <em>Settings → TLS</em> once install completes.</p>
      </div>
      <div>
        <div class="eyebrow mb-1 text-foreground">iOS / Android</div>
        <p>AirDrop or copy the file to your device; both platforms then require you
          to enable the profile in Settings → General → About → Certificate Trust.</p>
      </div>
    </div>

    <Alert variant="info" class="mb-8">
      <AlertDescription>
        You can skip this and install the root CA later from
        <em>Settings → TLS</em>. Browsers will show a warning until you do.
      </AlertDescription>
    </Alert>

    <div class="flex items-center justify-between">
      <Button variant="ghost" @click="back">Back</Button>
      <Button variant="primary" size="lg" @click="next">Continue</Button>
    </div>
  </div>
</template>
