<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useSystemStore } from '@/stores/system';
import { useOnboardingStore } from '@/stores/onboarding';
import Button from '@/components/ui/Button.vue';
import Alert from '@/components/ui/Alert.vue';

const system = useSystemStore();
const store = useOnboardingStore();
const router = useRouter();

const err = ref<string | null>(null);

onMounted(async () => {
  try {
    if (!system.info) await system.fetchInfo();
  } catch (e) {
    err.value = e instanceof Error ? e.message : 'Failed to read system info';
  }
});

function proceed(): void {
  store.next();
  router.push(`/onboarding/${store.currentStep}`);
}

const notDebian = () => {
  const d = system.info?.distro?.toLowerCase() ?? '';
  return d && !d.includes('debian') && !d.includes('ubuntu');
};
</script>

<template>
  <div>
    <div class="eyebrow mb-3">Step 1 of 9</div>
    <h1 class="mb-4">Welcome to Aurora.</h1>
    <p class="text-ink-2 text-base leading-relaxed mb-8">
      Aurora is the admin panel for this box. It's opinionated on purpose — most homelab
      setups fail on the same handful of decisions, so we make them for you and get out
      of your way.
    </p>

    <div v-if="err">
      <Alert tone="err" class="mb-6">{{ err }}</Alert>
    </div>

    <div v-else-if="!system.info" class="text-sm text-ink-4 mb-8">Reading system info…</div>

    <div v-else class="border border-line rounded-lg mb-8">
      <dl class="divide-y divide-[var(--color-line-2)]">
        <div class="grid grid-cols-3 gap-4 px-5 py-3 text-sm">
          <dt class="text-ink-3">Hostname</dt>
          <dd class="col-span-2 font-mono text-ink">{{ system.info.hostname }}</dd>
        </div>
        <div class="grid grid-cols-3 gap-4 px-5 py-3 text-sm">
          <dt class="text-ink-3">LAN IP</dt>
          <dd class="col-span-2 font-mono text-ink">{{ system.info.lanIp }}</dd>
        </div>
        <div class="grid grid-cols-3 gap-4 px-5 py-3 text-sm">
          <dt class="text-ink-3">Distribution</dt>
          <dd class="col-span-2 font-mono text-ink">{{ system.info.distro }}</dd>
        </div>
        <div class="grid grid-cols-3 gap-4 px-5 py-3 text-sm">
          <dt class="text-ink-3">Kernel</dt>
          <dd class="col-span-2 font-mono text-ink">{{ system.info.kernel }}</dd>
        </div>
        <div class="grid grid-cols-3 gap-4 px-5 py-3 text-sm">
          <dt class="text-ink-3">Docker</dt>
          <dd class="col-span-2 font-mono text-ink">{{ system.info.dockerVersion }}</dd>
        </div>
      </dl>
    </div>

    <Alert v-if="notDebian()" tone="warn" title="Untested distribution" class="mb-8">
      Aurora is designed for Debian and Ubuntu. Other distros may work, but the host
      Ansible playbooks and firewall roles assume Debian's package layout.
    </Alert>

    <div class="flex justify-end">
      <Button size="lg" variant="primary" @click="proceed">
        Continue
      </Button>
    </div>
  </div>
</template>
