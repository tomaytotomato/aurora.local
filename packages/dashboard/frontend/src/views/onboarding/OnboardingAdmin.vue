<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useOnboardingStore } from '@/stores/onboarding';
import { OnboardingApi } from '@/api/onboarding';
import Button from '@/components/ui/Button.vue';
import Input from '@/components/ui/Input.vue';
import Label from '@/components/ui/Label.vue';
import { Alert, AlertDescription } from '@/components/ui';
import Checkbox from '@/components/ui/Checkbox.vue';
import { generatePassword, copyToClipboard } from '@/lib/utils';
import { humanCopyForError } from '@/lib/http-error-copy';
import { toast } from '@/composables/useToast';

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
// The one-time recovery code the server issues with the account. It exists
// for exactly one render: there is no endpoint that will hand it back, by
// design, so the view must not navigate away before the operator has seen
// and acknowledged it.
const recoveryCode = ref<string | null>(null);
const recoveryAcknowledged = ref(false);
const recoveryCopied = ref(false);
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
    toast({ description: 'Admin password copied to clipboard.', variant: 'success', duration: 3000 });
    setTimeout(() => { copied.value = false; }, 2000);
  } else {
    toast({
      title: "Couldn't copy automatically",
      description: 'Select the password and copy manually (Ctrl+C or ⌘C).',
      variant: 'destructive',
      duration: 8000,
    });
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
    let created: { recoveryCode?: string };
    try {
      created = await OnboardingApi.setAdmin({ username: username.value, password: password.value });
    } catch (e) {
      err.value = humanCopyForError(e, { subject: 'your admin account', action: 'create' });
      return;
    }
    store.admin = {
      username: username.value,
      password: password.value,
      savedAcknowledged: savedAcknowledged.value,
    };
    // Re-hydrate so the rest of the wizard sees bootstrap_mode = false.
    try { await store.hydrate(); } catch { /* soft */ }

    if (created.recoveryCode) {
      // Stop here and show it. Advancing first would burn the only chance
      // the operator gets to write down the thing that saves them later.
      recoveryCode.value = created.recoveryCode;
      return;
    }
    store.next();
    router.push(`/onboarding/${store.currentStep}`);
  } finally {
    busy.value = false;
  }
}

async function copyRecovery(): Promise<void> {
  if (!recoveryCode.value) return;
  recoveryCopied.value = await copyToClipboard(recoveryCode.value);
  if (recoveryCopied.value) {
    toast({ description: 'Recovery code copied.', variant: 'success', duration: 3000 });
    setTimeout(() => { recoveryCopied.value = false; }, 2000);
  }
}

function continueAfterRecovery(): void {
  if (!recoveryAcknowledged.value) {
    err.value = 'Please confirm you have saved the recovery code.';
    return;
  }
  err.value = null;
  store.next();
  router.push(`/onboarding/${store.currentStep}`);
}

function back(): void { store.back(); router.push(`/onboarding/${store.currentStep}`); }
</script>

<template>
  <div>
    <div class="eyebrow mb-3">{{ store.stepEyebrow }}</div>
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
          Aurora doesn't store your password anywhere it can hand back. If you've
          lost it, use the recovery code from first-run: it's the
          <em>Forgot your password?</em> link on the sign-in page.
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

    <!-- Branch C: account created, one-time recovery code on screen. -->
    <template v-else-if="recoveryCode">
      <p class="text-foreground mb-8">
        Your account is created. This is the only thing that can get you back in
        if you ever lose that password, and it is the only time it will be shown.
      </p>

      <Alert v-if="err" variant="destructive" class="mb-6">
        <AlertDescription>{{ err }}</AlertDescription>
      </Alert>

      <div class="border border-border rounded-lg p-6 mb-6 bg-muted/40" data-test="recovery-code-panel">
        <div class="eyebrow mb-2">Recovery code</div>
        <div class="flex items-center gap-3">
          <code class="font-mono text-base text-foreground flex-1 break-all" data-test="recovery-code">{{ recoveryCode }}</code>
          <button
            type="button"
            class="text-xs text-muted-foreground hover:text-foreground px-2 py-1 rounded border border-border bg-card shrink-0"
            data-test="recovery-code-copy"
            @click="copyRecovery"
          >{{ recoveryCopied ? 'Copied' : 'Copy' }}</button>
        </div>
        <p class="mt-3 text-xs text-muted-foreground">
          Six words. Keep it wherever you keep the password — a password manager,
          or written down somewhere safe at home. Using it sets a new password and
          gives you a fresh code.
        </p>
      </div>

      <label class="flex items-start gap-3 cursor-pointer">
        <Checkbox v-model="recoveryAcknowledged" class="mt-0.5" />
        <span class="text-sm text-foreground">
          I've saved this recovery code somewhere I can find it later.
        </span>
      </label>

      <div class="mt-10 flex items-center justify-end">
        <Button variant="primary" size="lg" data-test="recovery-continue" @click="continueAfterRecovery">
          Continue
        </Button>
      </div>
    </template>

    <!-- Branch B: bootstrap mode. Real create form. -->
    <template v-else>
      <p class="text-foreground mb-8">
        One user. One password. No email recovery, no SMS. Aurora gives you a
        recovery code on the next screen — six words that can set a new password
        if you ever lose this one.
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

  </div>
</template>
