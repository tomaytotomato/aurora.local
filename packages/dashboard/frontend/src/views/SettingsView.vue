<script setup lang="ts">
import { useAuthStore } from '@/stores/auth';
import { useSystemStore } from '@/stores/system';
import Card from '@/components/ui/Card.vue';
import Button from '@/components/ui/Button.vue';
import Alert from '@/components/ui/Alert.vue';
import { useRouter } from 'vue-router';
import { computed } from 'vue';

const auth = useAuthStore();
const system = useSystemStore();
const router = useRouter();

const info = computed(() => system.info);

async function signOut(): Promise<void> {
  await auth.logout();
  router.push('/login');
}
</script>

<template>
  <section>
    <div class="mb-10">
      <div class="eyebrow mb-2">Preferences</div>
      <h1>Settings</h1>
    </div>

    <div class="space-y-6 max-w-2xl">
      <Card class="p-8">
        <div class="eyebrow mb-2">Admin</div>
        <h3 class="mb-2">Account</h3>
        <div class="text-sm text-ink-3 mb-4">
          Signed in as <span class="font-mono text-ink">{{ auth.session?.username ?? '—' }}</span>.
        </div>
        <Button variant="secondary" size="sm" @click="signOut">Sign out</Button>
      </Card>

      <Card class="p-8">
        <div class="eyebrow mb-2">Passkey</div>
        <h3 class="mb-2">Second factor</h3>
        <Alert tone="info">Passkey enrollment lands in v0.2.</Alert>
      </Card>

      <Card v-if="info" class="p-8">
        <div class="eyebrow mb-2">System</div>
        <h3 class="mb-4">Metadata</h3>
        <dl class="text-sm space-y-2">
          <div class="flex justify-between"><dt class="text-ink-3">Hostname</dt><dd class="font-mono">{{ info.hostname }}</dd></div>
          <div class="flex justify-between"><dt class="text-ink-3">Domain</dt><dd class="font-mono">{{ info.domain }}</dd></div>
          <div class="flex justify-between"><dt class="text-ink-3">LAN IP</dt><dd class="font-mono">{{ info.lanIp }}</dd></div>
          <div class="flex justify-between"><dt class="text-ink-3">Kernel</dt><dd class="font-mono">{{ info.kernel }}</dd></div>
          <div class="flex justify-between"><dt class="text-ink-3">Docker</dt><dd class="font-mono">{{ info.dockerVersion }}</dd></div>
        </dl>
      </Card>
    </div>
  </section>
</template>
