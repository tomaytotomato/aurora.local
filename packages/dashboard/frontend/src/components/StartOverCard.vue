<script setup lang="ts">
/**
 * The in-dashboard "Start over" (A8, closing A6).
 *
 * Doctrine (ESSENCE.md): "every CLI affordance in the user journey is
 * treated as a defect". Before A8 the only way back to a clean box was
 * `bash scripts/reset.sh`, which is a terminal step Sarah cannot take.
 * This card is the button-shaped equivalent.
 *
 * The whole thing is a footgun on purpose: it destroys accounts, mail,
 * DNS settings, and anything else stored on this box. So the flow makes
 * that unambiguous:
 *
 *   1. The card itself carries the danger-zone framing.
 *   2. The confirmation modal lists exactly what is about to happen (and
 *      what will survive), and requires the operator to type the word
 *      RESET verbatim before the button un-greys.
 *   3. Once accepted, we swap to a full-screen "disconnecting" splash
 *      that stays visible until the container dies — because the
 *      alternative is the router bouncing back to /login the moment
 *      /api/health starts failing, which reads as a mundane sign-out
 *      rather than the deliberate wipe it actually is.
 *
 * The last step (running `bash bootstrap.sh install` to bring Aurora
 * back) is spelled out on the splash, deliberately not inside a copyable
 * command box — bringing the box back is the one bit that cannot be
 * button-shaped, because Aurora has to be gone to be brought back.
 */
import { ref, computed } from 'vue';

import { ResetApi } from '@/api/reset';
import Card from '@/components/ui/Card.vue';
import Button from '@/components/ui/Button.vue';
import { Alert, AlertDescription, Dialog, Input, Label } from '@/components/ui';

/** Word the caller has to type. Must match ResetService#CONFIRM_TOKEN. */
const CONFIRM_WORD = 'RESET';

const showModal = ref(false);
const typed = ref('');
const busy = ref(false);
const err = ref<string | null>(null);

/**
 * True once the reset has been accepted by the backend. From this
 * moment we hide every other UI element and show only the "goodbye"
 * splash — trying to keep polling in the background is pointless
 * because the box is about to disappear, and any 502/network error
 * would look like a bug instead of the intended outcome.
 */
const disconnecting = ref(false);

const canSubmit = computed(() => typed.value === CONFIRM_WORD && !busy.value);

function openModal(): void {
  typed.value = '';
  err.value = null;
  showModal.value = true;
}

function closeModal(): void {
  if (busy.value) return; // cannot cancel a wipe in flight
  showModal.value = false;
}

async function submit(): Promise<void> {
  if (!canSubmit.value) return;
  busy.value = true;
  err.value = null;
  try {
    await ResetApi.start(CONFIRM_WORD);
    // Success = the helper container is about to start deleting things.
    // Swap the whole card for the goodbye splash and leave it up.
    disconnecting.value = true;
    showModal.value = false;
  } catch (e: unknown) {
    // The backend has not touched anything if the helper failed to
    // start. Tell the operator that plainly rather than leaving them
    // wondering whether they now have a half-wiped box.
    const status = (e as { response?: { status?: number } })?.response?.status;
    const message = (e as { response?: { data?: { message?: string } } })
      ?.response?.data?.message;
    if (status === 400) {
      err.value = 'Type RESET (all caps) to confirm.';
    } else if (status === 401 || status === 403) {
      err.value = 'You need to be signed in as an admin to reset this box.';
    } else {
      err.value = message
        ? `Aurora could not start the reset: ${message}. Nothing on this box has changed.`
        : 'Aurora could not start the reset. Nothing on this box has changed.';
    }
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <!-- The goodbye splash. Rendered as a full-viewport overlay because
       the router is about to lose its backend and any other UI element
       reading /api/* would flip to an error state as containers die. -->
  <div
    v-if="disconnecting"
    class="fixed inset-0 z-50 flex items-center justify-center bg-background/95 backdrop-blur-sm px-6"
    data-test="reset-goodbye"
  >
    <div class="max-w-xl text-center space-y-6">
      <h2 class="text-2xl font-semibold">Aurora is wiping this box.</h2>
      <p class="text-muted-foreground">
        The dashboard is about to disappear from your browser. That's normal —
        it's part of the wipe. Any tab still open on <span class="font-mono">aurora.local</span>
        will stop responding within a few seconds.
      </p>
      <div class="text-left border rounded-lg p-5 bg-muted/40 space-y-2">
        <p class="font-semibold">To bring Aurora back afterwards</p>
        <p class="text-sm text-muted-foreground">
          Sign in to this box the same way you did the first time (SSH, a
          keyboard, however you got in) and run the installer again:
        </p>
        <pre
          class="font-mono text-xs bg-background/80 border rounded p-2 whitespace-pre-wrap"
        >bash bootstrap.sh install</pre>
        <p class="text-xs text-muted-foreground">
          That's the same command the very first install used. The repo is still
          on the box; only the state it created has been deleted.
        </p>
      </div>
      <p class="text-xs text-muted-foreground">You can close this tab.</p>
    </div>
  </div>

  <Card class="p-8 border-destructive/40" data-card="start-over">
    <h3 class="card-title mb-1 text-destructive">Start over</h3>
    <p class="card-subtitle mb-4">Wipe this box back to a fresh clone</p>

    <p class="text-sm text-muted-foreground mb-3">
      Deletes every app installed here, along with its data — mail, accounts,
      DNS settings, backups that live on this box, and the TLS root Aurora
      generated. There is no undo. Files on other machines (a NAS you copied
      to, an off-site backup) are untouched.
    </p>
    <p class="text-sm text-muted-foreground mb-4">
      Kept: the repository itself, docker, the firewall, and everything the
      host role set up. Bringing Aurora back is a single command Aurora will
      show you after you confirm.
    </p>

    <Button
      variant="danger"
      size="sm"
      data-test="start-over-open"
      @click="openModal"
    >Reset this box…</Button>
  </Card>

  <Dialog
    :open="showModal"
    @update:open="(v: boolean) => { if (!v) closeModal(); }"
  >
    <template #title>Wipe this box?</template>
    <template #description>
      This is irreversible. Everything below will be deleted; Aurora
      will not ask again.
    </template>

    <div class="space-y-4">
      <div class="text-sm">
        <p class="font-semibold mb-1 text-destructive">
          Aurora will delete
        </p>
        <ul class="list-disc pl-5 space-y-1 text-muted-foreground">
          <li>Every app installed on this box, and every container it left behind</li>
          <li>Every mailbox, every account (including yours), every recovery code</li>
          <li>The TLS root — browsers that trusted this box will need to trust the new one</li>
          <li>DNS settings, backups stored on this box, and anything under <span class="font-mono">data/</span></li>
        </ul>
      </div>

      <div class="text-sm">
        <p class="font-semibold mb-1">Aurora will keep</p>
        <ul class="list-disc pl-5 space-y-1 text-muted-foreground">
          <li>The machine itself, its network, docker, and the firewall</li>
          <li>The repository — nothing you edited in this repo is touched</li>
          <li>Files on other machines (NAS, off-site backup) — Aurora cannot reach them from here</li>
        </ul>
      </div>

      <Alert variant="destructive" data-test="start-over-warning">
        <AlertDescription>
          <strong>There is no undo.</strong> The dashboard will disappear
          from your browser once you confirm.
        </AlertDescription>
      </Alert>

      <Alert v-if="err" variant="destructive" data-test="start-over-error">
        <AlertDescription>{{ err }}</AlertDescription>
      </Alert>

      <div>
        <Label for="start-over-confirm">
          Type <span class="font-mono">{{ CONFIRM_WORD }}</span> to confirm
        </Label>
        <Input
          id="start-over-confirm"
          v-model="typed"
          autocomplete="off"
          autocorrect="off"
          autocapitalize="off"
          spellcheck="false"
          data-test="start-over-confirm-input"
          @keydown.enter="submit"
        />
      </div>
    </div>

    <template #footer>
      <Button variant="ghost" :disabled="busy" @click="closeModal">Cancel</Button>
      <Button
        variant="danger"
        :disabled="!canSubmit"
        :loading="busy"
        data-test="start-over-confirm"
        @click="submit"
      >{{ busy ? 'Wiping…' : 'Wipe this box' }}</Button>
    </template>
  </Dialog>
</template>
