<script setup lang="ts">
/**
 * The hosted marketplace catalogue, on the Settings page.
 *
 * This is the consent surface for plan point 6: Aurora fetches and
 * verifies a newer catalogue index but never applies it. The operator
 * sees what version is active, what version is waiting, and chooses to
 * accept it. Accepting swaps what the marketplace shows and what a fresh
 * install would install — it never touches a running app, upgrades an
 * image, or rewrites an .env (plan point 7). The card says so, because a
 * person asked to click "accept" deserves to know exactly how far the
 * blast radius reaches.
 *
 * When the feature is off server-side (the default), the card renders a
 * short explanation and a link to the docs rather than nothing — an empty
 * card is a worse answer than "here is what this would do if you turned it
 * on".
 */
import { computed, onMounted } from 'vue';

import { useMarketplaceStore } from '@/stores/marketplace';
import { provenanceLine } from '@/api/marketplace';
import { relTime } from '@/lib/utils';
import { toast } from '@/composables/useToast';
import Card from '@/components/ui/Card.vue';
import Button from '@/components/ui/Button.vue';
import { Alert, AlertDescription, Badge, Skeleton } from '@/components/ui';

const store = useMarketplaceStore();

const status = computed(() => store.status);
const provenance = computed(() => (status.value ? provenanceLine(status.value) : ''));

onMounted(() => {
  void store.ensureLoaded();
});

async function refresh(): Promise<void> {
  await store.refresh();
  if (store.error) {
    toast({ title: "Couldn't reach the catalogue host", description: 'The box kept its last catalogue. Try again shortly.' });
  }
}

async function accept(): Promise<void> {
  try {
    await store.accept();
    toast({ title: 'Catalogue updated', description: `Now on ${store.status?.activeVersion ?? 'the latest version'}. No apps were changed.` });
  } catch {
    toast({ title: "Couldn't accept the update", description: 'Nothing changed on the box. Try refreshing and accepting again.' });
  }
}
</script>

<template>
  <Card class="p-8" id="marketplace" data-card="marketplace">
    <div class="flex items-baseline justify-between mb-3 gap-4">
      <div>
        <h3 class="card-title mb-1">App marketplace</h3>
        <p class="text-xs text-muted-foreground mt-1 max-w-xl">
          The catalogue of apps you can install is a signed, versioned list Aurora
          can fetch on its own schedule — separate from the dashboard's own updates.
          New apps can arrive between dashboard releases, and you decide when to take them.
        </p>
      </div>
      <Button
        v-if="status?.enabled"
        variant="secondary"
        size="sm"
        :disabled="store.busy"
        data-test="marketplace-refresh"
        @click="refresh"
      >{{ store.busy ? 'Checking…' : 'Check for updates' }}</Button>
    </div>

    <!-- Loading -->
    <div v-if="store.loading && !status" class="space-y-2">
      <Skeleton class="h-4 w-64" />
      <Skeleton class="h-4 w-40" />
    </div>

    <!-- Feature off (server default) -->
    <div v-else-if="status && !status.enabled" class="text-sm text-muted-foreground">
      <p class="mb-2">
        The hosted app marketplace is turned off on this box. Aurora shows the apps that
        ship with it, and nothing reaches out to the network — the built-in apps still work.
      </p>
      <p class="text-xs">
        Turning it on is safe to leave for later. When it is on, every fetched catalogue is
        checked for a valid signature before it is shown, and accepting an update never
        changes an app you are already running.
      </p>
    </div>

    <!-- Feature on -->
    <div v-else-if="status" class="space-y-4">
      <!-- Provenance -->
      <dl class="text-sm space-y-2">
        <div class="flex justify-between gap-4">
          <dt class="text-muted-foreground">Active catalogue</dt>
          <dd class="font-mono text-right">{{ status.activeVersion ?? '—' }}</dd>
        </div>
        <div class="flex justify-between gap-4">
          <dt class="text-muted-foreground">Apps</dt>
          <dd>{{ status.appCount }}</dd>
        </div>
        <div class="flex justify-between gap-4">
          <dt class="text-muted-foreground">Signature</dt>
          <dd>
            <Badge :tone="status.signatureValid ? 'ok' : 'err'">
              {{ status.signatureValid ? 'verified' : 'unverified' }}
            </Badge>
          </dd>
        </div>
        <div v-if="status.lastFetchedAt" class="flex justify-between gap-4">
          <dt class="text-muted-foreground">Last checked</dt>
          <dd>{{ relTime(status.lastFetchedAt) }}</dd>
        </div>
        <div v-if="status.source" class="flex justify-between gap-4">
          <dt class="text-muted-foreground">Source</dt>
          <dd class="font-mono">{{ status.source }}</dd>
        </div>
      </dl>

      <!-- Fetch error (held, not fatal) -->
      <Alert v-if="status.lastFetchError" variant="warning" data-test="marketplace-fetch-error">
        <AlertDescription>
          The last check couldn't complete ({{ status.lastFetchError }}). Aurora is
          still showing the catalogue it last verified.
        </AlertDescription>
      </Alert>

      <!-- Pending update — the consent moment -->
      <div
        v-if="status.updateAvailable"
        class="rounded-lg border border-info/30 bg-info/5 p-4"
        data-test="marketplace-update"
      >
        <div class="flex items-start justify-between gap-4 mb-2">
          <div>
            <p class="text-sm font-medium">
              A newer catalogue is ready
              <span class="font-mono text-muted-foreground">({{ status.availableVersion }})</span>
            </p>
            <p class="text-xs text-muted-foreground mt-1">
              <template v-if="(status.availableNewAppCount ?? 0) > 0">
                {{ status.availableNewAppCount }} new
                app{{ status.availableNewAppCount === 1 ? '' : 's' }} to browse.
              </template>
              Accepting updates what the marketplace shows. It does not upgrade,
              restart, or change any app you already have installed.
            </p>
          </div>
          <Button
            size="sm"
            :disabled="store.busy"
            data-test="marketplace-accept"
            @click="accept"
          >{{ store.busy ? 'Applying…' : 'Accept' }}</Button>
        </div>
      </div>
      <p v-else class="text-xs text-muted-foreground">{{ provenance }} — up to date.</p>
    </div>
  </Card>
</template>
