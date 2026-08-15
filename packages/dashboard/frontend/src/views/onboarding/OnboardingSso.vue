<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useOnboardingStore } from '@/stores/onboarding';
import { OnboardingApi } from '@/api/onboarding';
import { humanCopyForError } from '@/lib/http-error-copy';
import { toast } from '@/composables/useToast';
import Button from '@/components/ui/Button.vue';
import Checkbox from '@/components/ui/Checkbox.vue';
import { Alert, AlertTitle, AlertDescription } from '@/components/ui';

// Phase D iter-11 (D10). Onboarding "Single sign-on for services" step.
//
// Opt-in on this screen means:
//   * The identity package (Authelia) joins the enabled[] list.
//   * Aurora generates the three Authelia secrets on the server side
//     (AUTHELIA_JWT_SECRET / SESSION_SECRET / STORAGE_ENCRYPTION_KEY)
//     and writes them into packages/identity/.env with 0600 perms.
//   * When D11+ services (Notes, Grafana, Paperless, Forgejo, HA)
//     bring their vhosts up, Caddy forward-auth in front of Authelia
//     gates them. The user signs into Aurora once and every service
//     trusts that session.
//
// Skipping is fine — the user can enable identity later from
// Packages → identity → Enable, and Aurora will generate secrets
// on demand via IdentitySecretsService.

const store = useOnboardingStore();
const router = useRouter();

// Recommended default: opt-in. Homelab operators who take the time
// to walk the wizard almost always want SSO; making the checkbox
// pre-ticked shortens the happy path by one click.
const enableSso = ref(true);
const submitting = ref(false);
const error = ref<string | null>(null);

// Reflect the current draft state — a returning visitor who already
// completed this step sees the box in the shape they left it. Reads off
// the server-truth draft (there is no local picker selection any more).
const alreadyEnabled = computed(() =>
  store.draft?.enabled_packages?.includes('identity') ?? false,
);
if (alreadyEnabled.value) enableSso.value = true;

async function proceed(): Promise<void> {
  submitting.value = true;
  error.value = null;
  try {
    // Backend generates + persists the secrets when enableSso is true;
    // when false it's a no-op. The endpoint updates the enabled[]
    // list in .state.yml as a side effect.
    await OnboardingApi.setSso({ enable: enableSso.value });
    if (enableSso.value) {
      toast({
        title: 'SSO enabled',
        description: 'Authelia will front every protected service.',
        variant: 'success',
        duration: 4000,
      });
    }
    store.next();
    await router.push(`/onboarding/${store.currentStep}`);
  } catch (err) {
    error.value = humanCopyForError(err, { subject: 'SSO', action: 'enable' });
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div>
    <div class="eyebrow mb-3">{{ store.stepEyebrow }}</div>
    <h1 class="mb-4">One password for every service.</h1>
    <p class="text-foreground mb-6 max-w-3xl">
      Aurora can manage a single login page that gates every service
      on this box — SilverBullet, Grafana, Paperless, Forgejo, Home
      Assistant, everything under <code>*.{{ store.draft?.domain ?? 'aurora.local' }}</code>.
      You sign in once. Each service trusts the session and skips its
      own login page.
    </p>

    <div class="border border-border rounded-lg p-6 mb-6 bg-muted/40">
      <label class="flex items-start gap-3 cursor-pointer" data-test="onboarding-sso-toggle">
        <Checkbox v-model="enableSso" class="mt-0.5" />
        <div class="flex-1">
          <div class="text-sm font-medium text-foreground">
            Turn on single sign-on
          </div>
          <p class="text-sm text-muted-foreground mt-1">
            Adds the <code>identity</code> package (Authelia) and generates
            three strong secrets. You can add TOTP or a passkey after
            first login for a second factor.
          </p>
        </div>
      </label>
    </div>

    <Alert v-if="enableSso" variant="info" class="mb-6" data-test="onboarding-sso-info">
      <AlertTitle>What Aurora does when you continue</AlertTitle>
      <AlertDescription>
        <ol class="list-decimal ml-4 mt-2 space-y-1">
          <li>Adds <code>identity</code> to the packages list (you'll see it in Review).</li>
          <li>Generates <code>AUTHELIA_JWT_SECRET</code>, <code>AUTHELIA_SESSION_SECRET</code>,
            and <code>AUTHELIA_STORAGE_ENCRYPTION_KEY</code> — 32 random bytes each — and
            writes them to <code>packages/identity/.env</code> with owner-only perms.</li>
          <li>Every protected package's Caddy vhost gets forward-auth to Authelia
            when it comes up.</li>
        </ol>
      </AlertDescription>
    </Alert>

    <Alert v-else variant="warning" class="mb-6" data-test="onboarding-sso-skip-info">
      <AlertTitle>You can turn this on later</AlertTitle>
      <AlertDescription>
        Without SSO, each service uses its own login (SilverBullet's
        <code>SB_USER</code>, Grafana's admin/admin, etc.) and you'll
        maintain those credentials separately. Enable identity later
        from <em>Packages → identity</em> to switch on SSO without
        redoing the wizard.
      </AlertDescription>
    </Alert>

    <Alert v-if="error" variant="destructive" class="mb-6" data-test="onboarding-sso-error">
      <AlertDescription>{{ error }}</AlertDescription>
    </Alert>

    <div class="mt-10 flex items-center justify-between">
      <Button
        variant="ghost"
        :disabled="submitting"
        @click="() => { store.back(); router.push(`/onboarding/${store.currentStep}`); }"
      >Back</Button>
      <Button
        variant="primary"
        size="lg"
        :loading="submitting"
        data-test="onboarding-sso-continue"
        @click="proceed"
      >Continue</Button>
    </div>
  </div>
</template>
