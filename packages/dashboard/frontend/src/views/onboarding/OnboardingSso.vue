<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useOnboardingStore } from '@/stores/onboarding';
import { OnboardingApi, type SsoEnrollment } from '@/api/onboarding';
import Button from '@/components/ui/Button.vue';
import { Alert, AlertDescription } from '@/components/ui';

// "Set up SSO" — register the first second factor.
//
// This step exists because of a footgun that made a fresh box unusable.
// Every *.DOMAIN vhost is `policy: two_factor`, so reaching any service
// requires a registered second factor. Authelia delivers its enrollment
// link through a `notifier`, and on a LAN appliance with no mail that
// notifier is `filesystem`: the link lands in a file inside the Authelia
// container. The result was that services were not awkward to sign into,
// they were impossible — unless you happened to know to run
// `docker exec authelia cat /data/notification.txt`.
//
// So the wizard reads that file for you and hands you the link.
//
// Placement: after 'review', because review is what launches core.
// Authelia is not running before that, so an earlier step would be
// asking you to enroll against a container that does not exist.

const store = useOnboardingStore();
const router = useRouter();

const status = ref<SsoEnrollment | null>(null);
const err = ref<string | null>(null);
const checking = ref(false);
let timer: number | undefined;

const portalUrl = computed(() => `https://auth.${store.domain}/`);
const enrolled = computed(() => status.value?.enrolled === true);
const waitingForAuthelia = computed(() => status.value !== null && !status.value.autheliaUp);

async function refresh(): Promise<void> {
  checking.value = true;
  try {
    status.value = await OnboardingApi.ssoStatus();
    err.value = null;
  } catch {
    // Non-fatal by design. This step is a convenience over a file read;
    // if it fails the operator can still use the portal directly, and
    // the Skip path below stays open. Surfacing an axios string here
    // would only make a working box look broken.
    err.value = "Couldn't read enrollment status.";
  } finally {
    checking.value = false;
  }
}

onMounted(() => {
  void refresh();
  // Poll while the operator is off in the portal registering. 3s is
  // frequent enough that the step advances on its own the moment they
  // finish, and cheap enough that it costs nothing — it reads one small
  // file and one row count on the same box.
  timer = window.setInterval(() => void refresh(), 3000);
});

onUnmounted(() => {
  if (timer) window.clearInterval(timer);
});

function next(): void { store.next(); router.push(`/onboarding/${store.currentStep}`); }
function back(): void { store.back(); router.push(`/onboarding/${store.currentStep}`); }
</script>

<template>
  <div>
    <div class="eyebrow mb-3">{{ store.stepEyebrow }}</div>
    <h1 class="mb-4">Set up single sign-on.</h1>
    <p class="text-foreground mb-8">
      Your apps — mail, notes, everything you add later — sit behind
      <strong>Aurora SSO</strong>. One sign-in, one password, every service.
      It needs a second factor before it will let anyone through, so let's
      register one now. This takes about a minute and you only do it once.
    </p>

    <!-- Enrolled: the happy end state. -->
    <div
      v-if="enrolled"
      class="border border-border rounded-lg p-6 mb-8 bg-muted/40"
      data-test="sso-enrolled"
    >
      <div class="flex items-start gap-3">
        <span class="text-xl leading-none" aria-hidden="true">✓</span>
        <div>
          <div class="font-medium text-foreground mb-1">
            {{ status?.passkeyCount ? 'Passkey registered' : 'Second factor registered' }}
          </div>
          <p class="text-sm text-muted-foreground">
            Aurora SSO is ready. Every app you install from here on is protected by
            it automatically — you won't have to do this again.
          </p>
        </div>
      </div>
    </div>

    <!-- Authelia not up yet. Only reachable if someone lands here before
         the launch finished; the poll clears it on its own. -->
    <Alert v-else-if="waitingForAuthelia" class="mb-8" data-test="sso-waiting">
      <AlertDescription>
        Waiting for the SSO service to finish starting. This usually takes a few
        seconds after install.
      </AlertDescription>
    </Alert>

    <template v-else>
      <ol class="space-y-5 mb-8">
        <li class="flex gap-4">
          <span class="shrink-0 w-6 h-6 rounded-full bg-muted text-foreground text-xs
                       flex items-center justify-center font-medium">1</span>
          <div class="min-w-0">
            <div class="font-medium text-foreground mb-1">Open the sign-in portal</div>
            <p class="text-sm text-muted-foreground mb-2">
              Sign in as <code class="bg-muted px-1 py-0.5 rounded border border-border">{{ store.admin?.username || 'your admin user' }}</code>
              with the password you chose earlier.
            </p>
            <a :href="portalUrl" target="_blank" rel="noopener noreferrer">
              <Button variant="secondary" size="sm">Open Aurora SSO ↗</Button>
            </a>
            <!-- Honest about the one way this step fails on a fresh box:
                 the portal lives at a subdomain, and until this box is the
                 network's DNS server (the last screen sets that up) a
                 Linux or Android browser cannot resolve one. Better to say
                 so than to leave someone staring at a browser error on the
                 second-to-last step. -->
            <p class="text-xs text-muted-foreground mt-2">
              If that page doesn't open, this device can't look up
              <code>auth.{{ store.domain }}</code> yet. Skip this for now, finish
              the last screen — it shows you how to point your network at this
              box — then come back from Settings.
            </p>
          </div>
        </li>

        <li class="flex gap-4">
          <span class="shrink-0 w-6 h-6 rounded-full bg-muted text-foreground text-xs
                       flex items-center justify-center font-medium">2</span>
          <div class="min-w-0">
            <div class="font-medium text-foreground mb-1">Register a passkey</div>
            <p class="text-sm text-muted-foreground">
              The portal will ask you to add one. Choose a passkey if your device
              offers it — Touch ID, Windows Hello, or your phone. An authenticator
              app works too.
            </p>
          </div>
        </li>

        <li class="flex gap-4">
          <span class="shrink-0 w-6 h-6 rounded-full bg-muted text-foreground text-xs
                       flex items-center justify-center font-medium">3</span>
          <div class="min-w-0">
            <div class="font-medium text-foreground mb-1">Open the confirmation link</div>
            <p class="text-sm text-muted-foreground mb-2">
              Aurora SSO sends one to confirm it's really you. This box has no mail
              server yet, so Aurora picks it up for you and shows it here.
            </p>

            <div
              v-if="status?.pendingUrl"
              class="border border-border rounded-lg p-4 bg-muted/40"
              data-test="sso-pending-link"
            >
              <div class="eyebrow mb-2">Your confirmation link</div>
              <a
                :href="status.pendingUrl"
                target="_blank"
                rel="noopener noreferrer"
                class="font-mono text-xs break-all text-foreground hover:underline"
              >{{ status.pendingUrl }}</a>
              <div class="mt-3">
                <a :href="status.pendingUrl" target="_blank" rel="noopener noreferrer">
                  <Button size="sm">Open link ↗</Button>
                </a>
              </div>
            </div>

            <p v-else class="text-sm text-muted-foreground italic" data-test="sso-awaiting-link">
              Waiting for a link… it'll appear here automatically once you start
              registering.
            </p>
          </div>
        </li>
      </ol>

      <Alert v-if="err" class="mb-6"><AlertDescription>{{ err }}</AlertDescription></Alert>
    </template>

    <div class="flex items-center gap-3">
      <Button variant="secondary" @click="back">Back</Button>
      <Button data-test="sso-next" @click="next">
        {{ enrolled ? 'Continue' : 'Skip for now' }}
      </Button>
      <span v-if="checking && !enrolled" class="text-xs text-muted-foreground">checking…</span>
    </div>

    <!-- Skippable on purpose. A wizard that will not let you leave is a
         trap, and someone re-running onboarding may already have a factor
         on a device that is not to hand. Settings > Account re-surfaces
         this, and the Done page flags it as outstanding. -->
    <p v-if="!enrolled" class="text-xs text-muted-foreground mt-4">
      You can do this later from Settings, but apps stay locked until you do.
    </p>
  </div>
</template>
