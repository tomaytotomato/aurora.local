<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useOnboardingStore } from '@/stores/onboarding';
import { OnboardingApi } from '@/api/onboarding';
import Button from '@/components/ui/Button.vue';
import Input from '@/components/ui/Input.vue';
import Label from '@/components/ui/Label.vue';
import { Alert, AlertDescription, Dialog } from '@/components/ui';
import Checkbox from '@/components/ui/Checkbox.vue';
import { generatePassword, copyToClipboard } from '@/lib/utils';

const store = useOnboardingStore();
const router = useRouter();

// Branch mode. The server tells us whether an admin already exists via the
// hydrated draft. In bootstrap mode we show the create form; otherwise we
// show a read-only card because the server will 409 any re-creation attempt
// and we don't have the previously-set password (it lived in memory on the
// original page load, never in storage). The user must use the in-app
// password recovery flow (or, until that ships, contact an admin) to reset.
const alreadyCreated = computed(
  () => store.hydrated && store.draft && !store.draft.bootstrap_mode,
);
const savedUsername = computed(() => store.draft?.admin_username ?? store.admin?.username ?? null);

const username = ref(store.admin?.username ?? 'aurora');
const password = ref(store.admin?.password ?? generatePassword());
const savedAcknowledged = ref(store.admin?.savedAcknowledged ?? false);
const err = ref<string | null>(null);
const copied = ref(false);
const showRecovery = ref(false);
const copyFailed = ref(false);
const busy = ref(false);

const strengthPct = computed(() => Math.min(100, (password.value.length / 24) * 100));

function regenerate(): void {
  password.value = generatePassword();
  savedAcknowledged.value = false;
  copied.value = false;
  copyFailed.value = false;
}

async function copy(): Promise<void> {
  const ok = await copyToClipboard(password.value);
  copied.value = ok;
  copyFailed.value = !ok;
  if (ok) {
    setTimeout(() => { copied.value = false; }, 2000);
  }
}

async function proceed(): Promise<void> {
  err.value = null;

  // Fast-path: admin already created on the server. Nothing to submit —
  // just advance. Prevents the silent-409 misdirect where the user thinks
  // they've changed the password but the server ignored the write.
  if (alreadyCreated.value) {
    store.next();
    router.push(`/onboarding/${store.currentStep}`);
    return;
  }

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
    // Real submit path. Errors here are meaningful — surface them.
    try {
      await OnboardingApi.setAdmin({ username: username.value, password: password.value });
    } catch (e) {
      err.value = e instanceof Error ? e.message : 'Failed to create admin.';
      return;
    }
    store.admin = {
      username: username.value,
      password: password.value,
      savedAcknowledged: savedAcknowledged.value,
    };
    // Re-hydrate so the rest of the wizard sees bootstrap_mode = false.
    try { await store.hydrate(); } catch { /* soft */ }
    store.next();
    router.push(`/onboarding/${store.currentStep}`);
  } finally {
    busy.value = false;
  }
}

function back(): void { store.back(); router.push(`/onboarding/${store.currentStep}`); }
</script>

<template>
  <div>
    <div class="eyebrow mb-3">Step 2 of 9</div>
    <h1 class="mb-4">Create your admin account.</h1>

    <!-- Branch A: admin already exists. Show what we know, offer no form. -->
    <template v-if="alreadyCreated">
      <p class="text-foreground mb-8">
        An admin account is already set up on this box. You can keep going with
        the rest of the wizard.
      </p>

      <div class="border border-border rounded-lg p-6 mb-8 bg-muted/40">
        <div class="eyebrow mb-2">Existing admin</div>
        <div class="font-mono text-sm text-foreground">{{ savedUsername ?? 'admin' }}</div>
        <p class="mt-3 text-xs text-muted-foreground">
          Aurora doesn't store your password anywhere it can hand back. If
          you've lost it, use the
          <button type="button"
                  class="text-foreground underline underline-offset-2"
                  @click="showRecovery = true">password recovery</button>
          option to reset it.
        </p>
      </div>

      <Alert variant="info" class="mb-10">
        <AlertDescription>
          Password fields are hidden on purpose. The generated password from
          first-run only lives in the browser tab that created it &mdash; a
          refresh discards it, but the account itself is fine.
        </AlertDescription>
      </Alert>

      <div class="flex items-center justify-between">
        <Button variant="ghost" @click="back">Back</Button>
        <Button variant="primary" size="lg" @click="proceed">Continue</Button>
      </div>
    </template>

    <!-- Branch B: bootstrap mode. Real create form. -->
    <template v-else>
      <p class="text-foreground mb-8">
        One user. One password. No email recovery, no SMS. If you lose the
        password, use the
        <button type="button"
                class="text-foreground underline underline-offset-2"
                @click="showRecovery = true">password recovery</button>
        option on this screen to reset it.
      </p>

      <Alert v-if="err" variant="destructive" class="mb-6">
        <AlertDescription>{{ err }}</AlertDescription>
      </Alert>

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
              class="text-xs text-muted-foreground hover:text-foreground"
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
              class="absolute right-2 top-1/2 -translate-y-1/2 text-xs text-muted-foreground hover:text-foreground px-2 py-1 rounded border border-border bg-card"
              @click="copy"
            >
              {{ copyFailed ? 'Copy failed' : copied ? 'Copied' : 'Copy' }}
            </button>
          </div>
          <div class="mt-2 h-0.5 bg-secondary rounded-full overflow-hidden">
            <div
              class="h-full bg-foreground transition-all duration-300"
              :style="{ width: `${strengthPct}%` }"
            />
          </div>
          <p class="mt-2 text-xs text-muted-foreground">
            {{ password.length }} characters &middot; generated locally, never sent
            anywhere until you continue. <strong>If you refresh before clicking
            Continue, this password is gone.</strong>
          </p>
          <p v-if="copyFailed" class="mt-1 text-xs text-destructive">
            Couldn't copy automatically &mdash; select the password and copy manually (Ctrl+C).
          </p>
        </div>

        <label class="flex items-start gap-3 cursor-pointer">
          <Checkbox v-model="savedAcknowledged" class="mt-0.5" />
          <span class="text-sm text-foreground">
            I've saved this password in a password manager or somewhere I can find it later.
          </span>
        </label>
      </div>

      <div class="mt-10 flex items-center justify-between">
        <Button variant="ghost" @click="back">Back</Button>
        <Button variant="primary" size="lg" :loading="busy" @click="proceed">
          Continue
        </Button>
      </div>
    </template>

    <!-- Password recovery modal (shared by both branches). Sarah-safe copy:
         we promise an in-app path even before the recovery panel ships,
         so we don't leak a CLI escape hatch here. Ships as the shadcn
         Dialog primitive (focus trap + ESC + scroll lock). -->
    <Dialog v-model:open="showRecovery" data-test="recovery-dialog">
      <template #title>Password recovery</template>
      <template #description>
        Password recovery is coming to the dashboard shortly. In the
        meantime, if you've lost the admin password, ask whoever set this
        box up to reset it for you.
      </template>
      <template #footer>
        <Button variant="primary" @click="showRecovery = false">Got it</Button>
      </template>
    </Dialog>
  </div>
</template>
