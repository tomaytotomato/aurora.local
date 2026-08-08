<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { usePackagesStore } from '@/stores/packages';
import { useUpdatesStore } from '@/stores/updates';
import { ContainersApi, type ContainerInfo } from '@/api/containers';
import { PackagesApi, dockerStructureFor, isCorePackage, isRemovable, packageLinks } from '@/api/packages';
import { versionLabel } from '@/api/updates';
import { buildEnvForm, isEnvFormDirty, validateEnvForm } from '@/lib/envForm';
import { humanCopyForError } from '@/lib/http-error-copy';
import { packageLabel, prettyPackageName } from '@/lib/packageName';
import { toast } from '@/composables/useToast';
import Card from '@/components/ui/Card.vue';
import Badge from '@/components/ui/Badge.vue';
import Tabs from '@/components/ui/Tabs.vue';
import DockerBadge from '@/components/DockerBadge.vue';
import JobLogPanel from '@/components/JobLogPanel.vue';
import { Alert, AlertDescription, Button, Dialog, Input, Label, Skeleton } from '@/components/ui';

const route = useRoute();
const packages = usePackagesStore();
const updates = useUpdatesStore();

const err = ref<string | null>(null);
const activeTab = ref<'overview' | 'config' | 'logs' | 'related'>('overview');

const name = computed(() => route.params.name as string);
const detail = computed(() => packages.byName[name.value]);
const heading = computed(() =>
  detail.value ? packageLabel(detail.value) : prettyPackageName(name.value),
);

const isCore = computed(() => (detail.value ? isCorePackage(detail.value) : false));
const removable = computed(() => (detail.value ? isRemovable(detail.value) : false));
const structure = computed(() => (detail.value ? dockerStructureFor(detail.value) : 'container'));
// Core apps live on their own page now; send the back link to whichever
// one the operator actually came from.
const backTo = computed(() => (isCore.value ? '/apps/core' : '/apps/catalogue'));
const backLabel = computed(() => (isCore.value ? '← Core' : '← Apps'));
const links = computed(() => (detail.value ? packageLinks(detail.value) : []));

const readmeBody = computed(() => (detail.value?.readme ?? '').replace(/^#\s+.*\n+/, '').trim());

function portLabel(p: Record<string, unknown>): string {
  const host = p.host ?? p.port ?? '?';
  const proto = p.proto ?? 'tcp';
  const container = p.container;
  return container !== undefined && container !== host ? `${host} → ${container}/${proto}` : `${host}/${proto}`;
}

/** Read a numeric requirement (min_ram_mb / min_disk_gb) off the
 * manifest-derived `requires` bag. Honest-state: undefined renders
 * nothing rather than a fabricated zero. */
function requirement(key: string): number | undefined {
  const raw = detail.value?.requires?.[key];
  return typeof raw === 'number' ? raw : undefined;
}
const minRamMb = computed(() => requirement('min_ram_mb'));
const minDiskGb = computed(() => requirement('min_disk_gb'));

// ── Updates ───────────────────────────────────────────────────────────
// Read-only state from the updates domain. Applying one goes through the
// existing upgrade verb below; this only decides what the card says.
const update = computed(() => updates.byPackage[name.value]);
const updateAvailable = computed(() => update.value?.state === 'available');
const updateUnknown = computed(() => update.value?.state === 'unknown');

function checkedLabel(iso: string | null): string {
  if (!iso) return 'never';
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) return 'never';
  const hours = Math.floor((Date.now() - ms) / 3_600_000);
  if (hours < 1) return 'in the last hour';
  if (hours < 24) return `${hours} hour${hours === 1 ? '' : 's'} ago`;
  const days = Math.floor(hours / 24);
  return `${days} day${days === 1 ? '' : 's'} ago`;
}

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

// ── Config tab: env-var editor ────────────────────────────────────────
// Form state is a plain reactive record keyed by env var name so v-model
// works against dynamic keys. `baseline` tracks the last value Aurora
// confirmed the server holds (from the initial load, or from a save) so
// dirty-state and the reveal auto-fill below both compare against it
// rather than the raw initial snapshot.
const envLoading = ref(false);
const envLoaded = ref(false);
const envErr = ref<string | null>(null);
const form = reactive<Record<string, string>>({});
const baseline = reactive<Record<string, string>>({});
const revealed = reactive<Record<string, boolean>>({});
const revealedValues = ref<Record<string, string> | null>(null);
const saving = ref(false);

const envSpecs = computed(() => detail.value?.envVars ?? []);
const dirty = computed(() => isEnvFormDirty(form, baseline));
const errors = computed(() => validateEnvForm(envSpecs.value, form));
const hasErrors = computed(() => Object.keys(errors.value).length > 0);

function fieldLabel(key: string): string {
  return key
    .toLowerCase()
    .split('_')
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');
}

function resetEnvFormState(): void {
  for (const k of Object.keys(form)) delete form[k];
  for (const k of Object.keys(baseline)) delete baseline[k];
  for (const k of Object.keys(revealed)) delete revealed[k];
  revealedValues.value = null;
  envLoaded.value = false;
}

async function loadEnv(): Promise<void> {
  if (!detail.value || envLoading.value) return;
  envLoading.value = true;
  envErr.value = null;
  try {
    const values = await PackagesApi.env(detail.value.name);
    const built = buildEnvForm(envSpecs.value, values);
    Object.assign(form, built);
    Object.assign(baseline, built);
    envLoaded.value = true;
  } catch (e) {
    envErr.value = humanCopyForError(e, { subject: "this app's configuration", action: 'load' });
  } finally {
    envLoading.value = false;
  }
}

/** Secrets load masked; Reveal fetches the real values once and fills in
 * only the fields the operator hasn't already started editing, so a
 * half-typed replacement secret is never clobbered by the fetch. */
async function toggleReveal(key: string): Promise<void> {
  if (revealed[key]) {
    revealed[key] = false;
    return;
  }
  revealed[key] = true;
  if (revealedValues.value || !detail.value) return;
  try {
    revealedValues.value = await PackagesApi.env(detail.value.name, true);
    for (const spec of envSpecs.value) {
      if (spec.secret && form[spec.key] === baseline[spec.key]) {
        const real = revealedValues.value[spec.key];
        if (real !== undefined) {
          form[spec.key] = real;
          baseline[spec.key] = real;
        }
      }
    }
  } catch (e) {
    revealed[key] = false;
    toast({
      title: "Couldn't reveal",
      description: humanCopyForError(e, { subject: 'the real values', action: 'load' }),
      variant: 'destructive',
    });
  }
}

function resetEnvForm(): void {
  Object.assign(form, baseline);
}

async function saveEnv(): Promise<void> {
  if (!detail.value || hasErrors.value || !dirty.value) return;
  saving.value = true;
  try {
    await PackagesApi.setEnv(detail.value.name, { ...form });
    Object.assign(baseline, form);
    toast({ title: 'Saved', description: `${heading.value}'s configuration was updated.`, variant: 'success', duration: 3000 });
  } catch (e) {
    toast({
      title: "Couldn't save",
      description: humanCopyForError(e, { subject: 'this configuration', action: 'save' }),
      variant: 'destructive',
    });
  } finally {
    saving.value = false;
  }
}

watch(activeTab, (t) => {
  if (t === 'logs' && !containersLoaded.value && !containersLoading.value) {
    void loadContainers();
  }
  if (t === 'config' && !envLoaded.value && !envLoading.value) {
    void loadEnv();
  }
});

watch(name, () => {
  // Package change while sitting on a lazy tab — reset + refetch.
  containers.value = [];
  containersLoaded.value = false;
  resetEnvFormState();
  if (activeTab.value === 'logs') void loadContainers();
  if (activeTab.value === 'config') void loadEnv();
});

function cleanName(names: string[] | undefined): string {
  if (!names || names.length === 0) return '';
  const n = names[0];
  return n.startsWith('/') ? n.slice(1) : n;
}

// ── Lifecycle actions ──────────────────────────────────────────────────
// Core packages only ever get restart/upgrade; add/remove is gated by
// `removable` both here and in the template (belt + braces — the button
// simply doesn't render for core, but the handlers double-check too).
// Add, remove and update all return a job id and stream their log into
// the panel below the action bar, rather than reporting "started" in a
// toast and going quiet. Restart stays a plain 204: it is over in
// seconds and has nothing worth watching.
type Busy = 'enable' | 'disable' | 'restart' | 'upgrade' | null;
type JobAction = 'enable' | 'disable' | 'upgrade';
const busy = ref<Busy>(null);
const removeOpen = ref(false);
const activeJobId = ref<string | null>(null);
const activeAction = ref<JobAction | null>(null);

/** Actions are locked while a job of our own is still running. */
const jobRunning = ref(false);
const actionsLocked = computed(() => busy.value !== null || jobRunning.value);

async function refreshAfterLifecycle(): Promise<void> {
  await Promise.all([packages.fetchList(), packages.fetchOne(name.value)]);
}

const FAILURE_COPY: Record<JobAction, { title: string; subject: string; action: string }> = {
  enable: { title: "Couldn't add the app", subject: 'this app', action: 'enable' },
  disable: { title: "Couldn't remove the app", subject: 'this app', action: 'remove' },
  upgrade: { title: "Couldn't update the app", subject: 'this app', action: 'update' },
};

async function startJob(action: JobAction): Promise<void> {
  if (!detail.value) return;
  if (action === 'disable' && !removable.value) return;
  busy.value = action;
  try {
    const call = {
      enable: PackagesApi.enable,
      disable: PackagesApi.disable,
      upgrade: PackagesApi.upgrade,
    }[action];
    const { jobId } = await call(detail.value.name);
    activeAction.value = action;
    activeJobId.value = jobId;
    jobRunning.value = true;
  } catch (e) {
    const copy = FAILURE_COPY[action];
    toast({
      title: copy.title,
      description: humanCopyForError(e, { subject: copy.subject, action: copy.action }),
      variant: 'destructive',
    });
  } finally {
    busy.value = null;
    if (action === 'disable') removeOpen.value = false;
  }
}

const enablePackage = () => startJob('enable');
const confirmDisable = () => startJob('disable');
const upgradePackage = () => startJob('upgrade');

async function restartPackage(): Promise<void> {
  if (!detail.value) return;
  busy.value = 'restart';
  try {
    await PackagesApi.restart(detail.value.name);
    toast({ title: 'Restarting', description: `${heading.value} is restarting.`, variant: 'success', duration: 3000 });
  } catch (e) {
    toast({ title: "Couldn't restart the app", description: humanCopyForError(e, { subject: 'this app', action: 'restart' }), variant: 'destructive' });
  } finally {
    busy.value = null;
  }
}

const SUCCESS_COPY: Record<JobAction, (n: string) => { title: string; description: string }> = {
  enable: (n) => ({ title: 'Added', description: `${n} is up and running.` }),
  disable: (n) => ({ title: 'Removed', description: `${n} has been stopped and removed. Its data is still on disk.` }),
  upgrade: (n) => ({ title: 'Updated', description: `${n} is running the latest version.` }),
};

async function onJobSuccess(): Promise<void> {
  jobRunning.value = false;
  const action = activeAction.value;
  await refreshAfterLifecycle();
  if (action === 'upgrade') await updates.refreshOne(name.value);
  if (action) {
    const copy = SUCCESS_COPY[action](heading.value);
    toast({ ...copy, variant: 'success', duration: 4000 });
  }
}

async function onJobFailed(): Promise<void> {
  jobRunning.value = false;
  // No toast: the panel is already showing the failure and what to do
  // about it, and two accounts of the same problem is one too many.
  await refreshAfterLifecycle();
  if (activeAction.value === 'upgrade') await updates.refreshOne(name.value);
}

function retryJob(): void {
  const action = activeAction.value;
  activeJobId.value = null;
  if (action) void startJob(action);
}

function dismissJob(): void {
  activeJobId.value = null;
  activeAction.value = null;
}

watch(name, () => {
  // Navigating to a different app should not leave the previous app's
  // job log sitting on screen.
  activeJobId.value = null;
  activeAction.value = null;
  jobRunning.value = false;
});

onMounted(async () => {
  void updates.ensureLoaded();
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
    <div class="mb-8 on-photo">
      <router-link :to="backTo" class="text-xs text-white/70 no-underline hover:text-white">{{ backLabel }}</router-link>
      <div class="flex items-baseline gap-3 mt-4">
        <h1>{{ heading }}</h1>
        <Badge v-if="detail" :tone="detail.enabled ? 'ok' : 'neutral'" class="bg-card">
          {{ detail.enabled ? (detail.running ? 'running' : 'stopped') : 'disabled' }}
        </Badge>
        <Badge v-if="isCore" tone="info" class="bg-card">core</Badge>
      </div>
      <p v-if="detail" class="mt-2">{{ detail.description }}</p>
      <!-- VPN has a bespoke config surface at its own route; peer/QR
           management doesn't fit the generic env-var Config tab. -->
      <router-link
        v-if="name === 'vpn'"
        to="/vpn"
        class="inline-block mt-3 text-sm text-white/85 no-underline hover:text-white"
      >Full configuration →</router-link>
    </div>

    <Card v-if="err" class="p-6 mb-6">
      <Alert variant="destructive"><AlertDescription>{{ err }}</AlertDescription></Alert>
    </Card>

    <!-- Lifecycle action bar. Core apps never see add/remove — only the
         non-core branch offers it, and confirmDisable() double-checks
         `removable` regardless. -->
    <Card v-if="detail" class="p-5 mb-6 flex flex-wrap items-center justify-between gap-4" data-card="package-actions">
      <div class="flex items-center gap-3 flex-wrap">
        <DockerBadge :structure="structure" />
        <p class="text-xs text-muted-foreground">
          {{ isCore ? "Always on — can't be removed." : (detail.enabled ? 'Enabled on this box.' : 'Not currently running.') }}
        </p>
        <template v-if="links.length">
          <span class="text-muted-foreground text-xs">·</span>
          <a
            v-for="link in links"
            :key="link.label"
            :href="link.url"
            target="_blank"
            rel="noopener noreferrer"
            class="text-xs text-muted-foreground no-underline hover:text-foreground hover:underline"
          >{{ link.label }} ↗</a>
        </template>
      </div>
      <div class="flex items-center gap-2">
        <template v-if="isCore || detail.enabled">
          <Button size="sm" variant="secondary" :disabled="actionsLocked" data-test="package-restart" @click="restartPackage">
            {{ busy === 'restart' ? 'Restarting…' : 'Restart' }}
          </Button>
          <!-- Primary only when there is genuinely something to install;
               otherwise updating is a maintenance action, not the thing
               the page is inviting you to do. -->
          <Button
            size="sm"
            :variant="updateAvailable ? 'primary' : 'secondary'"
            :disabled="actionsLocked"
            data-test="package-upgrade"
            @click="upgradePackage"
          >{{ busy === 'upgrade' ? 'Updating…' : (updateAvailable ? 'Update' : 'Check and update') }}</Button>
        </template>
        <Button
          v-if="removable && detail.enabled"
          size="sm"
          variant="danger"
          :disabled="actionsLocked"
          data-test="package-remove"
          @click="removeOpen = true"
        >Remove</Button>
        <Button
          v-else-if="removable"
          size="sm"
          :disabled="actionsLocked"
          data-test="package-add"
          @click="enablePackage"
        >{{ busy === 'enable' ? 'Adding…' : 'Add app' }}</Button>
      </div>
    </Card>

    <!-- Live log for whichever of add / remove / update is in flight.
         Renders nothing at all until there is a job. -->
    <JobLogPanel
      v-if="activeJobId"
      :job-id="activeJobId"
      dismissible
      class="mb-6"
      @success="onJobSuccess"
      @failed="onJobFailed"
      @retry="retryJob"
      @dismiss="dismissJob"
    />

    <!--
      The tabbed region sits over the app-wide aurora photo. The tab
      strip stays transparent and uses on-photo-tabs for legible triggers
      (same as PackagesCatalogue's Installed/Marketplace tabs and
      VpnView); the panels below are opaque Cards. No opaque box around
      the tabs — it reads as a panel floating detached from the content
      cards under it.
    -->
    <Tabs
      v-model="activeTab"
      class="on-photo-tabs"
      :tabs="[
        { value: 'overview', label: 'Overview' },
        { value: 'config', label: 'Config' },
        { value: 'logs', label: 'Logs' },
        { value: 'related', label: 'Related' },
      ]"
    >
      <div v-if="activeTab === 'overview'">
        <div v-if="!detail" class="grid grid-cols-2 gap-4" data-state="loading" data-test="package-overview-skeleton">
          <Card>
            <Skeleton class="h-3 w-16 mb-2" />
            <Skeleton class="h-5 w-24 mb-3" />
            <Skeleton class="h-8 w-40" />
          </Card>
          <Card>
            <Skeleton class="h-3 w-16 mb-2" />
            <Skeleton class="h-5 w-24 mb-3" />
            <Skeleton class="h-4 w-48" />
          </Card>
        </div>
        <div v-else class="grid grid-cols-2 gap-4">
          <Card class="col-span-2">
            <div class="eyebrow mb-1">About</div>
            <h3 class="mb-3">What this is</h3>
            <p v-if="readmeBody" class="text-sm text-foreground whitespace-pre-line">{{ readmeBody }}</p>
            <p v-else class="text-sm text-muted-foreground">No description yet.</p>
          </Card>
          <!-- Versions. Absent entirely when the updates domain has
               nothing for this app: a card that says "no data" is worse
               than no card. -->
          <Card v-if="update" class="col-span-2" data-test="package-updates-card">
            <div class="eyebrow mb-1">Version</div>
            <div class="flex items-baseline gap-3 mb-3">
              <h3>{{ updateAvailable ? 'Update available' : (updateUnknown ? 'Version unknown' : 'Up to date') }}</h3>
              <Badge v-if="updateAvailable" tone="info">update</Badge>
              <Badge v-else-if="updateUnknown" tone="warn">unchecked</Badge>
            </div>

            <p v-if="updateUnknown" class="text-sm text-muted-foreground mb-3">
              Aurora couldn't reach the image registry last time it looked, so this is the version
              on the box rather than the newest one available.
            </p>

            <div
              v-if="update.lastUpdateFailed"
              data-tone="err"
              class="mb-3 px-4 py-3 rounded border border-destructive/25 bg-destructive/10 text-destructive text-sm"
              data-test="package-update-failed"
            >
              The last update attempt didn't finish, so this app is still on its previous version.
            </div>

            <ul class="text-sm space-y-1.5" data-test="package-update-images">
              <li
                v-for="img in update.images"
                :key="img.image"
                class="flex items-center justify-between gap-4"
              >
                <span class="font-mono text-xs text-muted-foreground truncate">{{ img.image }}</span>
                <span class="flex items-center gap-2 shrink-0">
                  <span class="font-mono">{{ versionLabel(img) }}</span>
                  <Badge v-if="img.pinned" tone="neutral">pinned</Badge>
                </span>
              </li>
            </ul>

            <p class="text-xs text-muted-foreground mt-3">
              Checked {{ checkedLabel(update.lastCheckedAt) }}.
              <template v-if="update.lastUpdatedAt"> Last updated {{ checkedLabel(update.lastUpdatedAt) }}.</template>
            </p>
          </Card>

          <Card>
            <div class="eyebrow mb-1">Runtime</div>
            <h3 class="mb-3">Status</h3>
            <div class="text-3xl font-mono text-foreground">{{ detail.running ? 'running' : 'stopped' }}</div>
          </Card>
          <Card>
            <div class="eyebrow mb-1">Docker</div>
            <h3 class="mb-3">Structure</h3>
            <DockerBadge :structure="structure" class="text-sm text-foreground mb-2" />
            <p class="text-xs text-muted-foreground">
              {{ structure === 'compose'
                ? 'Multiple services started together as one Docker Compose project.'
                : 'A single Docker container.' }}
            </p>
          </Card>
          <Card>
            <div class="eyebrow mb-1">Network</div>
            <h3 class="mb-3">vhosts</h3>
            <ul class="text-sm font-mono text-foreground space-y-0.5">
              <li v-for="v in (detail.vhosts ?? [])" :key="v">{{ v }}</li>
              <li v-if="!(detail.vhosts ?? []).length" class="text-muted-foreground">none</li>
            </ul>
          </Card>
          <Card>
            <div class="eyebrow mb-1">Network</div>
            <h3 class="mb-3">Ports</h3>
            <ul class="text-sm font-mono text-foreground space-y-0.5">
              <li v-for="(p, i) in (detail.ports ?? [])" :key="i">{{ portLabel(p) }}</li>
              <li v-if="!(detail.ports ?? []).length" class="text-muted-foreground">none</li>
            </ul>
          </Card>
          <Card>
            <div class="eyebrow mb-1">Depends on</div>
            <h3 class="mb-3">Dependencies</h3>
            <div class="flex gap-2 flex-wrap">
              <span v-for="d in (detail.dependsOn ?? [])" :key="d" class="font-mono text-xs px-2 py-1 rounded border border-border">{{ d }}</span>
              <span v-if="!(detail.dependsOn ?? []).length" class="text-muted-foreground text-sm">none</span>
            </div>
          </Card>
          <Card v-if="minRamMb !== undefined || minDiskGb !== undefined">
            <div class="eyebrow mb-1">Requirements</div>
            <h3 class="mb-3">Resources</h3>
            <dl class="text-sm space-y-2">
              <div v-if="minRamMb !== undefined" class="flex justify-between"><dt class="text-muted-foreground">Minimum RAM</dt><dd class="font-mono">{{ minRamMb }} MB</dd></div>
              <div v-if="minDiskGb !== undefined" class="flex justify-between"><dt class="text-muted-foreground">Minimum disk</dt><dd class="font-mono">{{ minDiskGb }} GB</dd></div>
            </dl>
          </Card>
        </div>
      </div>

      <div v-else-if="activeTab === 'config'">
        <Card v-if="isCore" class="p-4 mb-6">
          <Alert variant="info">
            <AlertDescription>Core apps can be configured but not removed — {{ heading }} keeps the rest of aurora running.</AlertDescription>
          </Alert>
        </Card>

        <Card v-if="envErr" class="p-6" data-state="error">
          <Alert variant="destructive"><AlertDescription>{{ envErr }}</AlertDescription></Alert>
          <Button size="sm" variant="secondary" class="mt-3" @click="loadEnv">Try again</Button>
        </Card>

        <div v-else-if="!envLoaded" class="space-y-3" data-state="loading" data-test="package-config-skeleton">
          <Skeleton class="h-16 w-full" />
          <Skeleton class="h-16 w-full" />
        </div>

        <Card v-else-if="!envSpecs.length" data-state="empty" class="p-8 text-center text-sm text-muted-foreground">
          This app has nothing to configure.
        </Card>

        <Card v-else class="p-6" data-test="package-config-form">
          <form class="space-y-5" @submit.prevent="saveEnv">
            <div v-for="spec in envSpecs" :key="spec.key" data-field="env-var">
              <Label :for="`env-${spec.key}`" :hint="spec.required ? 'required' : undefined">{{ fieldLabel(spec.key) }}</Label>
              <div class="flex gap-2">
                <Input
                  :id="`env-${spec.key}`"
                  v-model="form[spec.key]"
                  :type="spec.secret && !revealed[spec.key] ? 'password' : 'text'"
                  :placeholder="spec.example"
                  :invalid="!!errors[spec.key]"
                />
                <Button
                  v-if="spec.secret"
                  type="button"
                  variant="secondary"
                  size="sm"
                  :data-test="`reveal-${spec.key}`"
                  @click="toggleReveal(spec.key)"
                >{{ revealed[spec.key] ? 'Hide' : 'Reveal' }}</Button>
              </div>
              <p v-if="errors[spec.key]" class="text-xs text-destructive mt-1">{{ errors[spec.key] }}</p>
              <p v-else-if="spec.comment" class="text-xs text-muted-foreground mt-1">{{ spec.comment }}</p>
            </div>

            <div class="flex items-center gap-3 pt-2">
              <Button type="submit" :disabled="!dirty || hasErrors || saving" data-test="env-save">
                {{ saving ? 'Saving…' : 'Save' }}
              </Button>
              <Button type="button" variant="secondary" :disabled="!dirty || saving" data-test="env-cancel" @click="resetEnvForm">
                Cancel
              </Button>
            </div>
          </form>
        </Card>
      </div>

      <div v-else-if="activeTab === 'logs'">
        <!--
          B3-followup (iter-16): honest per-package containers list.
          The old 'lands with M3' Alert stayed too long — M3 shipped B1
          + B2 + B3 already, so this promise is due.
        -->
        <Card v-if="containersErr" data-state="error" role="alert" class="p-6 space-y-3">
          <Alert variant="destructive">
            <AlertDescription>{{ containersErr }}</AlertDescription>
          </Alert>
          <button
            type="button"
            class="text-sm text-foreground underline"
            @click="loadContainers"
          >Try again</button>
        </Card>
        <div
          v-else-if="!containersLoaded && (containersLoading || !detail)"
          data-state="loading"
          data-test="package-logs-skeleton"
          class="space-y-2"
        >
          <Skeleton class="h-14 w-full" />
          <Skeleton class="h-14 w-full" />
          <Skeleton class="h-14 w-2/3" />
        </div>
        <Card
          v-else-if="containers.length === 0"
          data-state="empty"
          class="p-8 text-center"
          data-test="package-logs-empty"
        >
          <p class="text-sm text-foreground mb-1">Nothing running for this app yet.</p>
          <p class="text-xs text-muted-foreground">
            Start the app first, then come back to see its logs here.
          </p>
        </Card>
        <Card v-else class="p-4">
          <ul class="space-y-2" data-test="package-logs-list">
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
        </Card>
      </div>

      <div v-else>
        <Card v-if="detail" class="p-6 text-sm text-muted-foreground">
          <div class="mb-2"><span class="eyebrow">Dependencies:</span></div>
          <div class="flex gap-2 flex-wrap">
            <span v-for="d in (detail.dependsOn ?? [])" :key="d" class="font-mono text-xs px-2 py-1 rounded border border-border">{{ d }}</span>
            <span v-if="!(detail.dependsOn ?? []).length" class="text-muted-foreground">none</span>
          </div>
        </Card>
      </div>
    </Tabs>

    <!-- Remove confirm — destructive, so it gets a dialog rather than a
         one-click button (same pattern as VpnView's rotate-key confirm). -->
    <Dialog :open="removeOpen" @update:open="removeOpen = $event">
      <template #title>Remove {{ heading }}?</template>
      <template #description>
        This stops and removes its containers. Its data stays on disk unless you also
        clear its volumes by hand.
      </template>
      <template #footer>
        <Button variant="secondary" @click="removeOpen = false">Cancel</Button>
        <Button variant="danger" :disabled="busy === 'disable'" data-test="confirm-remove" @click="confirmDisable">
          {{ busy === 'disable' ? 'Removing…' : 'Remove' }}
        </Button>
      </template>
    </Dialog>
  </section>
</template>
