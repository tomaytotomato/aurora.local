<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { humanCopyForStatus, httpStatusFromError } from '@/lib/http-error-copy';
import Button from '@/components/ui/Button.vue';
import Input from '@/components/ui/Input.vue';
import Label from '@/components/ui/Label.vue';
import Alert from '@/components/ui/Alert.vue';

const router = useRouter();
const auth = useAuthStore();

const username = ref('');
const password = ref('');
const err = ref<string | null>(null);
const busy = ref(false);
const passkeyToast = ref<string | null>(null);

async function submit(): Promise<void> {
  err.value = null;
  busy.value = true;
  try {
    await auth.login(username.value, password.value);
    router.push('/');
  } catch (e) {
    // iter-39: humane copy per §5 contract — no axios strings, no
    // e.message leaked into the DOM (that path used to expose
    // 'Request failed with status code 401' verbatim).
    const status = httpStatusFromError(e);
    if (status === 401 || status === 403) {
      err.value = "That username and password didn't match. Try again.";
    } else if (status !== undefined && status >= 500) {
      err.value = humanCopyForStatus(status, {
        subject: 'you',
        action: 'sign in',
      });
    } else if (e instanceof Error && e.message && !e.message.toLowerCase().includes('status')) {
      // Keep a plain-language error message if the caller went out of
      // its way to throw a nice string (e.g. offline detection).
      err.value = e.message;
    } else {
      err.value = "Aurora couldn't sign you in just now.";
    }
  } finally {
    busy.value = false;
  }
}

function passkey(): void {
  passkeyToast.value = 'Passkey sign-in lands in v0.2. Use password for now.';
  setTimeout(() => (passkeyToast.value = null), 4000);
}
</script>

<template>
  <div class="min-h-screen bg-canvas grid place-items-center px-6">
    <div class="w-full max-w-sm anim-enter">
      <div class="flex items-center gap-2.5 mb-10">
        <svg viewBox="0 0 32 32" class="w-7 h-7">
          <rect width="32" height="32" rx="6" fill="var(--color-ink)"/>
          <path d="M8 22 L16 8 L24 22" stroke="var(--color-on-ink)" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
          <circle cx="16" cy="22" r="1.5" fill="var(--color-accent)"/>
        </svg>
        <span class="font-serif text-xl leading-none text-ink">Aurora</span>
      </div>

      <h1 class="mb-2">Sign in</h1>
      <p class="text-ink-3 text-sm mb-8">
        The admin panel for this box.
        <span class="text-ink-4">Homepage lives at the root domain.</span>
      </p>

      <Alert v-if="err" tone="err" class="mb-4">{{ err }}</Alert>
      <Alert v-if="passkeyToast" tone="info" class="mb-4">{{ passkeyToast }}</Alert>

      <form class="space-y-4" @submit.prevent="submit">
        <div>
          <Label for="username">Username</Label>
          <Input id="username" v-model="username" autocomplete="username" autofocus />
        </div>
        <div>
          <Label for="password">Password</Label>
          <Input id="password" v-model="password" type="password" autocomplete="current-password" />
        </div>
        <Button type="submit" variant="primary" size="lg" class="w-full" :loading="busy">
          Sign in
        </Button>
        <Button type="button" variant="secondary" size="lg" class="w-full" @click="passkey">
          Sign in with passkey
        </Button>
      </form>

      <p class="mt-10 text-xs text-ink-4">
        First time here?
        <router-link to="/onboarding" class="text-ink-3">Start onboarding</router-link>.
      </p>
    </div>
  </div>
</template>
