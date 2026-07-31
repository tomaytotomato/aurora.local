<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useOnboardingStore } from '@/stores/onboarding';
import { OnboardingApi } from '@/api/onboarding';
import Button from '@/components/ui/Button.vue';
import Input from '@/components/ui/Input.vue';
import Label from '@/components/ui/Label.vue';
import Alert from '@/components/ui/Alert.vue';
import Checkbox from '@/components/ui/Checkbox.vue';
import { generatePassword, copyToClipboard } from '@/lib/utils';

const store = useOnboardingStore();
const router = useRouter();

const username = ref(store.admin?.username ?? 'aurora');
const password = ref(store.admin?.password ?? generatePassword());
const savedAcknowledged = ref(store.admin?.savedAcknowledged ?? false);
const err = ref<string | null>(null);
const copied = ref(false);
const busy = ref(false);

const strengthPct = computed(() => Math.min(100, (password.value.length / 24) * 100));

function regenerate(): void {
  password.value = generatePassword();
  savedAcknowledged.value = false;
  copied.value = false;
}

async function copy(): Promise<void> {
  copied.value = await copyToClipboard(password.value);
}

async function proceed(): Promise<void> {
  err.value = null;
  if (!username.value.trim()) {
    err.value = 'Username is required.';
    return;
  }
  if (password.value.length < 16) {
    err.value = 'Password must be at least 16 characters.';
    return;
  }
  if (!savedAcknowledged.value) {
    err.value = 'Please confirm you have saved the password before continuing.';
    return;
  }
  busy.value = true;
  try {
    // Best-effort persist; wizard flow keeps going even if backend is not up yet
    // (dev mode may run without a live backend).
    try {
      await OnboardingApi.setAdmin({ username: username.value, password: password.value });
    } catch { /* v0.1: soft-fail */ }
    store.admin = {
      username: username.value,
      password: password.value,
      savedAcknowledged: savedAcknowledged.value,
    };
    store.next();
    router.push(`/onboarding/${store.currentStep}`);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div>
    <div class="eyebrow mb-3">Step 2 of 9</div>
    <h1 class="mb-4">Create your admin account.</h1>
    <p class="text-ink-2 mb-8">
      One user. One password. No email recovery, no SMS. If you lose the password, you
      SSH in and reset it — that's the deal.
    </p>

    <Alert v-if="err" tone="err" class="mb-6">{{ err }}</Alert>

    <div class="space-y-6">
      <div>
        <Label for="uname">Username</Label>
        <Input id="uname" v-model="username" autocomplete="username" />
      </div>

      <div>
        <div class="flex items-center justify-between mb-1.5">
          <Label for="pw" class="mb-0">Password</Label>
          <button
            type="button"
            class="text-xs text-ink-3 hover:text-ink"
            @click="regenerate"
          >
            Generate new
          </button>
        </div>
        <div class="relative">
          <Input
            id="pw"
            v-model="password"
            type="text"
            autocomplete="new-password"
            class="pr-20 font-mono"
          />
          <button
            type="button"
            class="absolute right-2 top-1/2 -translate-y-1/2 text-xs text-ink-3 hover:text-ink px-2 py-1 rounded border border-line bg-surface"
            @click="copy"
          >
            {{ copied ? 'Copied' : 'Copy' }}
          </button>
        </div>
        <div class="mt-2 h-0.5 bg-[var(--color-line-2)] rounded-full overflow-hidden">
          <div
            class="h-full bg-[var(--color-ink)] transition-all duration-300"
            :style="{ width: `${strengthPct}%` }"
          />
        </div>
        <p class="mt-2 text-xs text-ink-4">
          {{ password.length }} characters · generated locally, never sent anywhere until
          you continue.
        </p>
      </div>

      <label class="flex items-start gap-3 cursor-pointer">
        <Checkbox v-model="savedAcknowledged" class="mt-0.5" />
        <span class="text-sm text-ink-2">
          I've saved this password in a password manager or somewhere I can find it later.
        </span>
      </label>
    </div>

    <div class="mt-10 flex items-center justify-between">
      <Button variant="ghost" @click="() => { store.back(); router.push(`/onboarding/${store.currentStep}`); }">
        Back
      </Button>
      <Button variant="primary" size="lg" :loading="busy" @click="proceed">
        Continue
      </Button>
    </div>
  </div>
</template>
