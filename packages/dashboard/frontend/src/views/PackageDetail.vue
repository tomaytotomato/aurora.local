<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { usePackagesStore } from '@/stores/packages';
import { ContainersApi, type ContainerInfo } from '@/api/containers';
import { humanCopyForError } from '@/lib/http-error-copy';
import Card from '@/components/ui/Card.vue';
import Badge from '@/components/ui/Badge.vue';
import Tabs from '@/components/ui/Tabs.vue';
import { Alert, AlertDescription } from '@/components/ui';

const route = useRoute();
const packages = usePackagesStore();

const err = ref<string | null>(null);
const activeTab = ref<'overview' | 'config' | 'logs' | 'related'>('overview');

const name = computed(() => route.params.name as string);
const detail = computed(() => packages.byName[name.value]);

// B3-followup (iter-16): Logs tab lists containers scoped to this package
// so the operator picks the right service (media stack = 7 containers).
// Each row links into /containers/:id/logs (B3, iter-11+12). Lazy loading:
// only fetch when the tab becomes active so the overview render isn't
// gated on a docker roundtrip.
const containers = ref<ContainerInfo[]>([]);
const containersLoaded = ref(false);
const containersErr = ref<string | null>(null);
const containersLoading = ref(false);

async function loadContainers(): Promise<void> {
  if (containersLoading.value) return;
  containersLoading.value = true;
  containersErr.value = null;
  try {
    containers.value = await ContainersApi.list(name.value);
    containersLoaded.value = true;
  } catch (e: unknown) {
    containersErr.value = humanCopyForError(e, {
      subject: "this package's containers",
      action: 'list',
    });
  } finally {
    containersLoading.value = false;
  }
}

watch(activeTab, (t) => {
  if (t === 'logs' && !containersLoaded.value && !containersLoading.value) {
    void loadContainers();
  }
});

watch(name, () => {
  // Package change while sitting on the Logs tab — reset + refetch.
  containers.value = [];
  containersLoaded.value = false;
  if (activeTab.value === 'logs') void loadContainers();
});

function cleanName(names: string[] | undefined): string {
  if (!names || names.length === 0) return '';
  const n = names[0];
  return n.startsWith('/') ? n.slice(1) : n;
}

onMounted(async () => {
  try {
    await packages.fetchOne(name.value);
  } catch (e) {
    err.value = humanCopyForError(e, {
      subject: 'this package',
      action: 'load',
    });
  }
});
</script>

<template>
  <section>
    <div class="mb-8">
      <router-link to="/packages" class="text-xs text-muted-foreground no-underline">← All packages</router-link>
      <div class="flex items-baseline gap-3 mt-4">
        <h1>{{ name }}</h1>
        <Badge v-if="detail" :tone="detail.enabled ? 'ok' : 'neutral'">
          {{ detail.enabled ? (detail.running ? 'running' : 'stopped') : 'disabled' }}
        </Badge>
      </div>
      <p v-if="detail" class="text-muted-foreground mt-2">{{ detail.description }}</p>
    </div>

    <Alert v-if="err" variant="destructive" class="mb-6">
      <AlertDescription>{{ err }}</AlertDescription>
    </Alert>

    <Tabs
      v-model="activeTab"
      :tabs="[
        { value: 'overview', label: 'Overview' },
        { value: 'config', label: 'Config' },
        { value: 'logs', label: 'Logs' },
        { value: 'related', label: 'Related' },
      ]"
    >
      <div v-if="activeTab === 'overview'">
        <div v-if="!detail" class="text-sm text-muted-foreground">Loading…</div>
        <div v-else class="grid grid-cols-2 gap-4">
          <Card>
            <div class="eyebrow mb-1">Runtime</div>
            <h3 class="mb-3">Status</h3>
            <div class="text-3xl font-mono text-foreground">{{ detail.running ? 'running' : 'stopped' }}</div>
          </Card>
          <Card>
            <div class="eyebrow mb-1">Network</div>
            <h3 class="mb-3">vhosts</h3>
            <ul class="text-sm font-mono text-foreground space-y-0.5">
              <li v-for="v in (detail.vhosts ?? [])" :key="v">{{ v }}</li>
              <li v-if="!(detail.vhosts ?? []).length" class="text-muted-foreground">none</li>
            </ul>
          </Card>
        </div>
      </div>

      <div v-else-if="activeTab === 'config'">
        <Alert variant="info">
          <AlertDescription>Env-form editor lands with M2 (see brief §6.2).</AlertDescription>
        </Alert>
      </div>

      <div v-else-if="activeTab === 'logs'">
        <!--
          B3-followup (iter-16): honest per-package containers list.
          The old 'lands with M3' Alert stayed too long — M3 shipped B1
          + B2 + B3 already, so this promise is due.
        -->
        <div v-if="containersErr" data-state="error" role="alert" class="space-y-3">
          <Alert variant="destructive">
            <AlertDescription>{{ containersErr }}</AlertDescription>
          </Alert>
          <button
            type="button"
            class="text-sm text-foreground underline"
            @click="loadContainers"
          >Try again</button>
        </div>
        <div
          v-else-if="!containersLoaded && (containersLoading || !detail)"
          data-state="empty"
          class="text-sm text-muted-foreground"
        >Loading…</div>
        <Card
          v-else-if="containers.length === 0"
          data-state="empty"
          class="p-8 text-center"
          data-test="package-logs-empty"
        >
          <p class="text-sm text-foreground mb-1">No containers for this package.</p>
          <p class="text-xs text-muted-foreground">
            Aurora only sees containers labelled with the compose project
            <span class="font-mono">aurora-{{ name }}</span>. Start the
            package first, then come back here.
          </p>
        </Card>
        <ul v-else class="space-y-2" data-test="package-logs-list">
          <li
            v-for="c in containers"
            :key="c.id"
            class="flex items-center justify-between gap-3 border border-border rounded-md px-4 py-3"
          >
            <div class="min-w-0">
              <div class="flex items-center gap-2">
                <span class="font-mono text-sm truncate">{{ cleanName(c.names) }}</span>
                <Badge :tone="c.state === 'running' ? 'ok' : 'neutral'">{{ c.state }}</Badge>
              </div>
              <div class="text-xs text-muted-foreground mt-0.5 truncate">{{ c.image }}</div>
            </div>
            <router-link
              :to="`/containers/${encodeURIComponent(cleanName(c.names))}/logs`"
              class="text-sm text-foreground no-underline hover:underline whitespace-nowrap"
              data-test="package-logs-link"
            >View logs →</router-link>
          </li>
        </ul>
      </div>

      <div v-else>
        <div v-if="detail" class="text-sm text-muted-foreground">
          <div class="mb-2"><span class="eyebrow">Dependencies:</span></div>
          <div class="flex gap-2 flex-wrap">
            <span v-for="d in (detail.dependsOn ?? [])" :key="d" class="font-mono text-xs px-2 py-1 rounded border border-border">{{ d }}</span>
            <span v-if="!(detail.dependsOn ?? []).length" class="text-muted-foreground">none</span>
          </div>
        </div>
      </div>
    </Tabs>
  </section>
</template>
