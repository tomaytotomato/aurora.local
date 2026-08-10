<script setup lang="ts">
/**
 * Point a friendly address at a container, without hand-editing
 * caddy.snippet.
 *
 * The file stays the source of truth: Aurora writes a fragment, Caddy's
 * existing --watch picks it up, and — the part that matters — the exact
 * fragment is shown before it is written. Dockge's argument is that
 * hiding real config behind a database is what makes these tools
 * untrustworthy, and it is a good argument, so the preview is not
 * optional decoration.
 *
 * Routes that come from a package manifest are listed but not editable.
 * Changing those means changing the package.
 */
import { computed, onMounted, ref, watch } from 'vue';

import {
  ProxyApi,
  blockingConflicts,
  customRoutes,
  validateSubdomain,
  type ProxyPreview,
  type ProxyRoute,
  type ProxyTarget,
} from '@/api/proxy';
import { humanCopyForError } from '@/lib/http-error-copy';
import { toast } from '@/composables/useToast';
import Card from '@/components/ui/Card.vue';
import Button from '@/components/ui/Button.vue';
import { Alert, AlertDescription, Badge, Dialog, Input, Label, Select, Skeleton } from '@/components/ui';

const routes = ref<ProxyRoute[]>([]);
const targets = ref<ProxyTarget[]>([]);
const loading = ref(true);
const loadErr = ref<string | null>(null);

const adding = ref(false);
const subdomain = ref('');
const target = ref('');
const preview = ref<ProxyPreview | null>(null);
const previewing = ref(false);
const creating = ref(false);
const removeTarget = ref<ProxyRoute | null>(null);

const mine = computed(() => customRoutes(routes.value));
const managed = computed(() => routes.value.filter((r) => r.managed));

const nameError = computed(() => (subdomain.value ? validateSubdomain(subdomain.value) : null));
const blockers = computed(() => (preview.value ? blockingConflicts(preview.value.conflicts) : []));
const advisories = computed(() =>
  preview.value ? preview.value.conflicts.filter((c) => c.advisory) : [],
);
const canCreate = computed(
  () => !!subdomain.value && !!target.value && !nameError.value && blockers.value.length === 0,
);

const targetOptions = computed(() =>
  targets.value.flatMap((t) =>
    t.ports.map((port) => ({
      value: `${t.container}:${port}`,
      label: `${t.container}:${port}${t.package ? ` (${t.package})` : ''}`,
    })),
  ),
);

async function load(): Promise<void> {
  loading.value = true;
  loadErr.value = null;
  try {
    const [r, t] = await Promise.all([ProxyApi.routes(), ProxyApi.targets()]);
    routes.value = r;
    targets.value = t;
  } catch (e) {
    loadErr.value = humanCopyForError(e, { subject: 'your addresses', action: 'load' });
  } finally {
    loading.value = false;
  }
}

onMounted(load);

/** Re-run the dry run whenever either field settles. */
async function refreshPreview(): Promise<void> {
  if (!subdomain.value || !target.value || nameError.value) {
    preview.value = null;
    return;
  }
  previewing.value = true;
  try {
    preview.value = await ProxyApi.preview(subdomain.value.trim().toLowerCase(), target.value);
  } catch {
    preview.value = null;
  } finally {
    previewing.value = false;
  }
}

watch([subdomain, target], () => {
  void refreshPreview();
});

function openAdd(): void {
  subdomain.value = '';
  target.value = targetOptions.value[0]?.value ?? '';
  preview.value = null;
  adding.value = true;
}

async function create(): Promise<void> {
  if (!canCreate.value) return;
  creating.value = true;
  try {
    const route = await ProxyApi.create(subdomain.value.trim().toLowerCase(), target.value);
    routes.value = [...routes.value, route];
    adding.value = false;
    toast({
      title: 'Address added',
      description: `${route.vhost} is live — Caddy picked it up on its own.`,
      variant: 'success',
      duration: 4000,
    });
  } catch (e) {
    toast({
      title: "Couldn't add that",
      description: humanCopyForError(e, { subject: 'this address', action: 'create' }),
      variant: 'destructive',
    });
  } finally {
    creating.value = false;
  }
}

async function confirmRemove(): Promise<void> {
  const route = removeTarget.value;
  if (!route) return;
  try {
    await ProxyApi.remove(route.id);
    routes.value = routes.value.filter((r) => r.id !== route.id);
  } catch (e) {
    toast({
      title: "Couldn't remove that",
      description: humanCopyForError(e, { subject: 'this address', action: 'remove' }),
      variant: 'destructive',
    });
  } finally {
    removeTarget.value = null;
  }
}
</script>

<template>
  <Card class="p-8" data-card="proxy-routes">
    <div class="flex items-baseline justify-between mb-4 gap-4">
      <div>
        <h3 class="card-title mb-1">Addresses</h3>
        <p class="text-xs text-muted-foreground mt-1">
          Which name points at which container. Apps bring their own; these are the ones you
          added.
        </p>
      </div>
      <Button variant="secondary" size="sm" data-test="proxy-add" @click="openAdd">Add</Button>
    </div>

    <Alert v-if="loadErr" variant="destructive" class="mb-3">
      <AlertDescription>{{ loadErr }}</AlertDescription>
    </Alert>

    <div v-else-if="loading" class="space-y-2 py-2" data-state="loading">
      <Skeleton v-for="n in 3" :key="`proxy-sk-${n}`" class="h-8 w-full" />
    </div>

    <template v-else>
      <ul v-if="mine.length" class="space-y-2 mb-5" data-test="proxy-custom">
        <li
          v-for="route in mine"
          :key="route.id"
          class="flex items-center justify-between gap-4 border border-border rounded-md px-4 py-2.5"
          :data-route="route.vhost"
        >
          <div class="min-w-0">
            <span class="font-mono text-sm">{{ route.vhost }}</span>
            <span class="text-muted-foreground text-xs"> → </span>
            <span class="font-mono text-xs text-muted-foreground">{{ route.target }}</span>
          </div>
          <Button size="sm" variant="danger" @click="removeTarget = route">Remove</Button>
        </li>
      </ul>

      <p v-else class="text-xs text-muted-foreground mb-5" data-state="empty">
        You haven't added any of your own yet.
      </p>

      <div class="eyebrow mb-2">From apps</div>
      <ul class="space-y-1.5" data-test="proxy-managed">
        <li
          v-for="route in managed"
          :key="route.id"
          class="flex items-center justify-between gap-4 text-xs"
        >
          <span class="font-mono">{{ route.vhost }}</span>
          <span class="flex items-center gap-2 text-muted-foreground">
            <span class="font-mono">{{ route.target }}</span>
            <Badge tone="neutral">{{ route.package }}</Badge>
          </span>
        </li>
      </ul>
    </template>

    <!-- Add, with the real fragment shown before anything is written. -->
    <Dialog :open="adding" @update:open="adding = $event">
      <template #title>Point an address at a container</template>

      <div class="space-y-4">
        <div>
          <Label for="proxy-sub">Name</Label>
          <div class="flex items-center gap-2">
            <Input
              id="proxy-sub"
              v-model="subdomain"
              placeholder="books"
              class="max-w-48"
              :invalid="!!nameError"
            />
            <span class="text-sm text-muted-foreground font-mono">.aurora.local</span>
          </div>
          <p v-if="nameError" class="text-xs text-destructive mt-1">{{ nameError }}</p>
        </div>

        <div>
          <Label for="proxy-target">Container</Label>
          <Select id="proxy-target" v-model="target" :options="targetOptions" />
        </div>

        <div v-for="c in blockers" :key="c.message">
          <Alert variant="destructive"><AlertDescription>{{ c.message }}</AlertDescription></Alert>
        </div>
        <div v-for="c in advisories" :key="c.message">
          <Alert variant="info"><AlertDescription>{{ c.message }}</AlertDescription></Alert>
        </div>

        <div v-if="preview">
          <div class="eyebrow mb-2">What gets written to caddy.snippet</div>
          <pre
            class="bg-foreground text-background font-mono text-xs px-3 py-2 rounded overflow-auto"
            data-test="proxy-preview"
          >{{ preview.snippet }}</pre>
          <p class="text-xs text-muted-foreground mt-2">
            The file stays the source of truth, and it is in git. Caddy reloads on its own when
            it changes.
          </p>
        </div>
        <p v-else-if="previewing" class="text-xs text-muted-foreground">Checking…</p>
      </div>

      <template #footer>
        <Button variant="secondary" @click="adding = false">Cancel</Button>
        <Button :disabled="!canCreate || creating" data-test="proxy-create" @click="create">
          {{ creating ? 'Adding…' : 'Add it' }}
        </Button>
      </template>
    </Dialog>

    <Dialog :open="removeTarget !== null" @update:open="removeTarget = $event ? removeTarget : null">
      <template #title>Remove {{ removeTarget?.vhost }}?</template>
      <template #description>
        The container carries on running; it just stops being reachable at this name.
      </template>
      <template #footer>
        <Button variant="secondary" @click="removeTarget = null">Cancel</Button>
        <Button variant="danger" data-test="proxy-confirm-remove" @click="confirmRemove">Remove</Button>
      </template>
    </Dialog>
  </Card>
</template>
