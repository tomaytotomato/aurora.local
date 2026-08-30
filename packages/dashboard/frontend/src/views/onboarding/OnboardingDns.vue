<script setup lang="ts">
import { ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useOnboardingStore } from '@/stores/onboarding';
import Button from '@/components/ui/Button.vue';
import Tabs from '@/components/ui/Tabs.vue';

const store = useOnboardingStore();
const router = useRouter();

const mode = ref<'adguard' | 'router' | 'mdns'>(store.dnsMode ?? 'adguard');
watch(() => store.dnsMode, (v) => { if (v && v !== mode.value) mode.value = v; });

const tabs = [
  { value: 'adguard' as const, label: 'AdGuard on this box' },
  { value: 'router' as const, label: "My router's DNS" },
  { value: 'mdns' as const, label: 'mDNS only' },
];

async function proceed(): Promise<void> {
  await store.patchDraft({ dns_mode: mode.value, step: 'tls' });
  store.next();
  router.push(`/onboarding/${store.currentStep}`);
}
function back(): void { store.back(); router.push(`/onboarding/${store.currentStep}`); }
</script>

<template>
  <div>
    <div class="eyebrow mb-3">{{ store.stepEyebrow }}</div>
    <h1 class="mb-4">Route DNS.</h1>
    <p class="text-foreground mb-8">
      Devices on your LAN need to know how to reach
      <code class="bg-muted px-1 py-0.5 rounded border border-border font-mono">*.{{ store.domain }}</code>.
      Pick the story that matches your network.
    </p>

    <Tabs v-model="mode" :tabs="tabs" class="mb-6">
      <div v-if="mode === 'adguard'" class="space-y-4">
        <p class="text-sm text-foreground">
          Best default. AdGuard runs on this box and rewrites
          <code>*.{{ store.domain }}</code> to your LAN IP. Point every device's DNS at
          this box and you're done.
        </p>
        <div class="border border-border rounded-lg p-4 bg-muted/40 text-sm">
          <div class="eyebrow mb-2">What Aurora will do</div>
          <ul class="text-muted-foreground space-y-1">
            <li>Install AdGuard Home on this box (it also blocks ads and trackers).</li>
            <li>Point <code>*.{{ store.domain }}</code> at this box, so every app has a name.</li>
            <li>Show you how to point your router at it, on the last screen.</li>
          </ul>
        </div>
      </div>

      <div v-else-if="mode === 'router'" class="space-y-4">
        <p class="text-sm text-foreground">
          You'll add a wildcard <code>A</code> record on your router:
          <code>*.{{ store.domain }}</code> → this box's LAN IP.
        </p>
        <div class="border border-border rounded-lg p-4 bg-muted/40 text-sm">
          <div class="eyebrow mb-2">Not every router supports wildcards</div>
          <ul class="text-muted-foreground space-y-1">
            <li>UniFi, pfSense, OPNsense: yes.</li>
            <li>Most consumer ASUS / Netgear: no — add each subdomain individually.</li>
            <li>OpenWRT: use dnsmasq's <code>address=/.aurora.local/</code> syntax.</li>
          </ul>
        </div>
      </div>

      <div v-else class="space-y-4">
        <p class="text-sm text-foreground">
          mDNS resolves the apex <code>{{ store.domain }}</code> only. Subdomains like
          <code>sonarr.{{ store.domain }}</code> will not resolve without DNS support.
        </p>
        <div class="border border-border rounded-lg p-4 bg-muted/40 text-sm">
          <div class="eyebrow mb-2">Workaround</div>
          <p class="text-muted-foreground">
            Aurora will generate an <code>/etc/hosts</code> snippet you can paste onto
            each client device. Fine for a laptop or two; painful for a household.
          </p>
        </div>
      </div>
    </Tabs>

    <div class="mt-8 flex items-center justify-between">
      <Button variant="ghost" @click="back">Back</Button>
      <Button variant="primary" size="lg" @click="proceed">Continue</Button>
    </div>
  </div>
</template>
