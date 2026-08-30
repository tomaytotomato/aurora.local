<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { usePackagesStore } from '@/stores/packages';
import { useSystemStore } from '@/stores/system';
import { useUpdatesStore } from '@/stores/updates';
import { ContainersApi, type ContainerInfo } from '@/api/containers';
import { PackagesApi, dockerStructureFor, isCorePackage, isRemovable, packageLinks } from '@/api/packages';
import { ServicesApi } from '@/api/services';
import { versionLabel } from '@/api/updates';
import {
  NetworkApi,
  egressLabel,
  egressTone,
  tunnelConsequences,
  untunnelConsequences,
  type PackageNetwork,
} from '@/api/network';
import { buildEnvForm, isEnvFormDirty, validateEnvForm } from '@/lib/envForm';
import { humanCopyForError } from '@/lib/http-error-copy';
import { packageLabel, prettyPackageName } from '@/lib/packageName';
import { deriveStatusLight, isInstalledView, packageActionSlots, type PackageAction } from '@/lib/packageLifecycle';
import { useServiceStatusStream } from '@/composables/useServiceStatusStream';
import { toast } from '@/composables/useToast';
import Card from '@/components/ui/Card.vue';
import Badge from '@/components/ui/Badge.vue';
import Tabs from '@/components/ui/Tabs.vue';
import DockerBadge from '@/components/DockerBadge.vue';
import StatusLight from '@/components/StatusLight.vue';
import JobLogPanel from '@/components/JobLogPanel.vue';
import PackageResourcesCard from '@/components/PackageResourcesCard.vue';
import PackageImpactPanel from '@/components/PackageImpactPanel.vue';
import PackagePreview from '@/components/PackagePreview.vue';
import MarkdownBlock from '@/components/MarkdownBlock.vue';
import { humanEnvLabel, cleanEnvHelp } from '@/lib/envCopy';
import { Alert, AlertDescription, Button, Dialog, Input, Label, Skeleton } from '@/components/ui';

const route = useRoute();
const system = useSystemStore();
const packages = usePackagesStore();
const updates = useUpdatesStore();

const err = ref<string | null>(null);
const activeTab = ref<'overview' | 'config' | 'network' | 'logs' | 'related'>('overview');

const name = computed(() => route.params.name as string);
const detail = computed(() => packages.byName[name.value]);
const heading = computed(() =>
  detail.value ? packageLabel(detail.value) : prettyPackageName(name.value),
);

const isCore = computed(() => (detail.value ? isCorePackage(detail.value) : false));
const removable = computed(() => (detail.value ? isRemovable(detail.value) : false));

// ── Control panel: status light + gated actions ───────────────────────
// /services/status carries the richer per-package probe (running /
// starting / failed / needs-config / not-started) that the plain
// enabled+running booleans on PackageDetail can't distinguish. SSE with
// a poll fallback, same composable the Done checklist and dashboard
// home already use.
const statusStream = useServiceStatusStream();
const probe = computed(() => statusStream.data.value?.services.find((s) => s.package === name.value));
const lightState = computed(() =>
  deriveStatusLight({
    loaded: !!detail.value,
    enabled: detail.value?.enabled ?? false,
    running: detail.value?.running ?? false,
    probeState: probe.value?.state,
  }),
);
const actionSlots = computed(() =>
  packageActionSlots({
    isCore: isCore.value,
    enabled: detail.value?.enabled ?? false,
    running: detail.value?.running ?? false,
  }),
);
// Which half of the page applies: the installed half (live state, logs,
// config, network, version/update status, backup coverage — everything
// below) or the preview half (PackagePreview — what installing it would
// cost, before any of the above exists to report on). Same route either
// way; this is a reactive flip on `enabled`, not a navigation, so a
// package that installs mid-session lands here with no reload. Core
// packages are always the installed half regardless of their enabled
// flag — see isInstalledView().
const installed = computed(() =>
  isInstalledView({ isCore: isCore.value, enabled: detail.value?.enabled ?? false }),
);
function actionSlot(action: PackageAction) {
  return actionSlots.value.find((s) => s.action === action)!;
}
const structure = computed(() => (detail.value ? dockerStructureFor(detail.value) : 'container'));
// Core apps live on their own page now; send the back link to whichever
// one the operator actually came from.
const backTo = computed(() => (isCore.value ? '/apps/core' : '/apps/catalogue'));
const backLabel = computed(() => (isCore.value ? '← Core' : '← Apps'));
const links = computed(() => (detail.value ? packageLinks(detail.value) : []));

// The backend serves packages/<name>/README.md verbatim, heading and all,
// so the leading `# Title` is stripped here rather than rendered above the
// one the header already shows.
//
// The README is *not* the About text. It is the owner's setup document —
// "copy .env.example to .env", `./scripts/up.sh privacy`, uncomment the
// devices: block for hardware transcoding — and rendering it as the first
// thing on an app's page put the densest concentration of terminal
// instructions in the product on the screen users visit most, three inches
// under a button that already does the job. About is the manifest's own
// one-paragraph description; the README sits in a closed disclosure for the
// person who wants it.
const readmeBody = computed(() => (detail.value?.readme ?? '').replace(/^#\s+.*\n+/, '').trim());
const aboutBody = computed(() => (detail.value?.description ?? '').trim());
const ownerNotes = computed(() => readmeBody.value);

// Primary CTA target for an installed + running app: the first vhost
// Caddy is serving for it. vhostsFor() in PackagesService already
// appends the current domain, so these are full FQDNs like
// `notes.aurora.local`. https:// because Caddy issues a cert via the
// core CA on every managed vhost; the trust-root install lives on the
// Done page and in Settings > Reach. When the package has no vhost
// (Samba, backup jobs, other backend-only apps) the button just does
// not render — there is nothing to open in a browser.
const openUrl = computed<string | null>(() => {
  if (!detail.value?.enabled || !detail.value?.running) return null;
  const host = (detail.value.vhosts ?? [])[0];
  return host ? `https://${host}/` : null;
});

/**
 * The address that works when the name does not.
 *
 * `<app>.<domain>` is a multi-label .local name, and glibc's stock
 * mdns4_minimal only resolves single-label ones — so on Linux and Android
 * clients the Open button above is a dead link until this box is their DNS
 * server. Rather than explain that in a paragraph nobody reads, offer the
 * address that never depends on name resolution.
 */
const openFallbackUrl = computed<string | null>(() => {
  if (!detail.value?.enabled || !detail.value?.running) return null;
  const ip = system.info?.lanIp;
  if (!ip) return null;
  const ports = detail.value.ports ?? [];
  for (const p of ports) {
    const proto = (p.proto ?? 'tcp') as string;
    if (proto.toLowerCase() !== 'tcp') continue;
    if (p.profile) continue;              // behind a compose profile: not running
    const port = (p.host ?? p.port) as number | undefined;
    if (typeof port !== 'number') continue;
    if (port === 80 || port === 443 || port === 53) continue;
    return `http://${ip}:${port}/`;
  }
  return null;
});

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
  return humanEnvLabel(key);
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

// ── Network tab: egress mode ──────────────────────────────────────────
// Whether this app's traffic leaves through the VPN gateway or the
// normal WAN route. The mechanism is netns sharing, which is why the
// switch is safe to offer at all — see docs/SPLIT_TUNNEL.md — but it is
// still a restart with real consequences, so it goes through a confirm
// that lists them rather than being a toggle you can knock with an
// elbow.
const network = ref<PackageNetwork | null>(null);
const networkLoading = ref(false);
const networkErr = ref<string | null>(null);
const tunnelConfirm = ref(false);

const tunnelled = computed(() => network.value?.mode === 'vpn');
const consequences = computed(() => {
  if (!network.value) return [];
  return tunnelled.value ? untunnelConsequences(network.value) : tunnelConsequences(network.value);
});

async function loadNetwork(): Promise<void> {
  if (networkLoading.value) return;
  networkLoading.value = true;
  networkErr.value = null;
  try {
    network.value = await NetworkApi.get(name.value);
  } catch (e) {
    networkErr.value = humanCopyForError(e, { subject: "this app's networking", action: 'load' });
  } finally {
    networkLoading.value = false;
  }
}

async function confirmEgressChange(): Promise<void> {
  if (!network.value) return;
  const next = tunnelled.value ? 'direct' : 'vpn';
  tunnelConfirm.value = false;
  try {
    const { jobId } = await NetworkApi.setMode(name.value, next);
    activeAction.value = null;
    activeJobId.value = jobId;
    jobRunning.value = true;
  } catch (e) {
    toast({
      title: "Couldn't change that",
      description: humanCopyForError(e, { subject: "this app's networking", action: 'update' }),
      variant: 'destructive',
    });
  }
}

watch(activeTab, (t) => {
  if (t === 'logs' && !containersLoaded.value && !containersLoading.value) {
    void loadContainers();
  }
  if (t === 'config' && !envLoaded.value && !envLoading.value) {
    void loadEnv();
  }
  if (t === 'network' && !network.value && !networkLoading.value) {
    void loadNetwork();
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
// Core packages only ever get restart/upgrade; install/start/disable/
// uninstall are gated by `actionSlot(...)` both here and in the template
// (belt + braces — the button simply doesn't render or is disabled for
// an invalid state, but the handlers double-check too). All four
// lifecycle verbs plus update return a job id and stream their log into
// the panel below the control card, rather than reporting "started" in a
// toast and going quiet. Restart stays a plain 204: it is over in
// seconds and has nothing worth watching.
type Busy = 'enable' | 'disable' | 'uninstall' | 'restart' | 'upgrade' | 'start' | null;
type JobAction = 'enable' | 'disable' | 'uninstall' | 'upgrade' | 'start' | 'restart';
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
  enable: { title: "Couldn't install the app", subject: 'this app', action: 'install' },
  disable: { title: "Couldn't stop the app", subject: 'this app', action: 'stop' },
  uninstall: { title: "Couldn't uninstall the app", subject: 'this app', action: 'uninstall' },
  upgrade: { title: "Couldn't update the app", subject: 'this app', action: 'update' },
  start: { title: "Couldn't start the app", subject: 'this app', action: 'start' },
  restart: { title: "Couldn't restart the app", subject: 'this app', action: 'restart' },
};

async function startJob(action: JobAction): Promise<void> {
  if (!detail.value) return;
  if ((action === 'uninstall' || action === 'disable') && !removable.value) return;
  busy.value = action;
  try {
    let jobId: string;
    if (action === 'start') {
      // POST /services/{package}/start replies with job_id (snake_case,
      // matching the wider services.ts shape), not the jobId camelCase
      // the packages.ts lifecycle verbs return.
      jobId = (await ServicesApi.start(detail.value.name)).job_id;
    } else {
      const call = {
        enable: PackagesApi.enable,
        // Disable (stop, stays enrolled) vs. Uninstall (stop + un-enrol)
        // are two different backend verbs — see lib/packageLifecycle.ts.
        disable: PackagesApi.stop,
        uninstall: PackagesApi.disable,
        upgrade: PackagesApi.upgrade,
        restart: PackagesApi.restart,
      }[action];
      jobId = (await call(detail.value.name)).jobId;
    }
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
    if (action === 'uninstall') removeOpen.value = false;
  }
}

// Installing an app now goes through a disclosure step. The manifest has
// always known what it would take — ports, other apps it drags in, host
// roles — and none of it was shown at the moment it mattered.
const addConfirm = ref(false);
const enablePackage = () => startJob('enable');
function askToAdd(): void {
  addConfirm.value = true;
}
function confirmAdd(): void {
  addConfirm.value = false;
  void enablePackage();
}
const confirmDisable = () => startJob('uninstall');
const upgradePackage = () => startJob('upgrade');
const startPackage = () => startJob('start');
// Disable (stop, stays enrolled) is reversible with a plain Start, so it
// gets a direct click like Start/Restart rather than a confirm dialog —
// Uninstall keeps the dialog because it un-enrols the app.
const disablePackage = () => startJob('disable');

// Restart goes through the same job flow as the rest now that the
// endpoint returns a JobRef: the log is the point, since a restart that
// fails halfway used to report a cheerful "is restarting" toast and
// nothing else.
const restartPackage = () => startJob('restart');

const SUCCESS_COPY: Record<JobAction, (n: string) => { title: string; description: string }> = {
  enable: (n) => ({ title: 'Installed', description: `${n} is up and running.` }),
  disable: (n) => ({ title: 'Stopped', description: `${n} has been stopped. Start it again any time — nothing was uninstalled.` }),
  uninstall: (n) => ({ title: 'Uninstalled', description: `${n} has been stopped and removed. Its data is still on disk.` }),
  upgrade: (n) => ({ title: 'Updated', description: `${n} is running the latest version.` }),
  start: (n) => ({ title: 'Started', description: `${n} is up and running.` }),
  restart: (n) => ({ title: 'Restarted', description: `${n} has been restarted.` }),
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
  network.value = null;
  networkErr.value = null;
  if (activeTab.value === 'network') void loadNetwork();
});

/**
 * Fetch this package's detail. Shared by onMounted and the `name`
 * watcher below — Vue Router reuses the PackageDetail instance when only
 * the `:name` param changes (going from /apps/media to /apps/identity
 * via a link, or browser back/forward between two app pages never
 * remounts the component), so onMounted alone only ever fires once. Without
 * this watcher, `detail` kept pointing at the previous app's data (or
 * nothing, if this was the first ever fetch) after such a navigation —
 * the whole page, including the control panel, silently went stale until
 * a full reload.
 */
async function loadDetail(): Promise<void> {
  err.value = null;
  try {
    await packages.fetchOne(name.value);
  } catch (e) {
    err.value = humanCopyForError(e, {
      subject: 'this package',
      action: 'load',
    });
  }
}

watch(name, () => {
  void loadDetail();
});

onMounted(async () => {
  void updates.ensureLoaded();
  await loadDetail();
});
</script>

<template>
  <section>
    <div class="mb-8 on-photo">
      <router-link :to="backTo" class="text-xs text-white/70 no-underline hover:text-white">{{ backLabel }}</router-link>
      <div class="flex items-baseline gap-3 mt-4 flex-wrap">
        <h1>{{ heading }}</h1>
        <StatusLight :state="lightState" class="bg-card" />
        <Badge v-if="isCore" tone="info" class="bg-card">core</Badge>
        <!-- Primary CTA: take the user straight to the app they just
             installed. Deliberately not in the actions Card below with
             Restart / Update / Disable / Uninstall — that group is
             maintenance, this one is "go use the thing". The button is
             absent (rather than disabled) when the app is not running
             or has no vhost, so the hero doesn't advertise a dead link. -->
        <a
          v-if="openUrl"
          :href="openUrl"
          target="_blank"
          rel="noopener noreferrer"
          class="ml-auto inline-flex items-center gap-1.5 rounded-md bg-white/95 text-slate-900 hover:bg-white px-3 py-1.5 text-sm font-medium no-underline shadow-sm"
          data-test="package-open"
        >Open {{ heading }} <span aria-hidden="true">↗</span></a>
      </div>
      <p v-if="openFallbackUrl" class="mt-2 text-sm text-white/80">
        Name not resolving on this device?
        <a
          :href="openFallbackUrl"
          target="_blank"
          rel="noopener noreferrer"
          class="text-white underline underline-offset-2"
          data-test="package-open-fallback"
        >{{ openFallbackUrl }}</a>
        always works.
      </p>
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

    <!-- Control panel skeleton: shown for the same window the Card below
         is absent (before the first GET /packages/{name} resolves), so
         landing on the page reads as "loading" rather than as nothing at
         all. Same data-state vocabulary as the tab panels below. -->
    <Card v-if="!detail && !err" class="p-5 mb-6" data-state="loading" data-test="package-actions-skeleton">
      <div class="flex items-center justify-between gap-4">
        <Skeleton class="h-5 w-40" />
        <Skeleton class="h-8 w-56" />
      </div>
    </Card>

    <!-- Control panel. Core apps get none of install/start/disable/
         uninstall — `actionSlot(...).visible` is false for all four when
         isCore, and the copy below explains why instead of just hiding
         them silently. Which of the remaining three are visible depends
         on enabled/running (see lib/packageLifecycle.ts); confirmDisable()
         double-checks `removable` regardless as a second guard. -->
    <Card v-else-if="detail" class="p-5 mb-6" data-card="package-actions">
      <div class="flex flex-wrap items-center justify-between gap-4">
        <div class="flex items-center gap-3 flex-wrap">
          <DockerBadge :structure="structure" />
          <p class="text-xs text-muted-foreground">
            {{ isCore ? "Always on — can't be added, started, stopped, or removed from here."
              : (detail.enabled ? (detail.running ? 'Enabled and running.' : 'Enabled, not currently running.') : 'Not installed.') }}
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
        <div class="flex items-center gap-2 flex-wrap">
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
            v-if="actionSlot('install').visible"
            size="sm"
            :disabled="actionsLocked"
            data-test="action-install"
            @click="askToAdd"
          >{{ busy === 'enable' ? 'Installing…' : 'Install' }}</Button>

          <Button
            v-if="actionSlot('start').visible"
            size="sm"
            variant="primary"
            :disabled="actionsLocked"
            data-test="action-start"
            @click="startPackage"
          >{{ busy === 'start' ? 'Starting…' : 'Start' }}</Button>

          <Button
            v-if="actionSlot('disable').visible"
            size="sm"
            variant="secondary"
            :disabled="actionsLocked || !actionSlot('disable').enabled"
            data-test="action-disable"
            @click="disablePackage"
          >{{ busy === 'disable' ? 'Stopping…' : 'Disable' }}</Button>

          <Button
            v-if="actionSlot('uninstall').visible"
            size="sm"
            variant="danger"
            :disabled="actionsLocked || !actionSlot('uninstall').enabled"
            data-test="action-uninstall"
            @click="removeOpen = true"
          >Uninstall</Button>
        </div>
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
      Not-installed apps get the preview half instead of the tabbed
      region below — Config, Network and Logs all describe something
      running, and installing hasn't happened yet. See PackagePreview.vue
      for what's shown instead, and lib/packageLifecycle.ts::isInstalledView
      for the flag this branches on. While `detail` is still loading,
      `installed` reads false but this stays on the Tabs branch (the `||
      !detail` below) so the existing Overview skeleton keeps doing its
      job rather than a second, competing loading state appearing here.
    -->
    <PackagePreview v-if="detail && !installed" :detail="detail" :update="update" />

    <!--
      A hard load error stops here. We already show the operator-facing
      message in the actions card above ("Aurora can't find this
      package on this box any more."); the tabbed region below would
      just render five never-resolving skeleton cards under that error,
      which reads as "loading + broken at the same time" — a genuinely
      confusing state, and the reason /apps/nonexistent used to look
      half-alive rather than plainly gone.
    -->
    <template v-else-if="err"></template>

    <!--
      The tabbed region sits over the app-wide aurora photo. The tab
      strip stays transparent and uses on-photo-tabs for legible triggers
      (same as PackagesCatalogue's Installed/Marketplace tabs and
      VpnView); the panels below are opaque Cards. No opaque box around
      the tabs — it reads as a panel floating detached from the content
      cards under it.
    -->
    <Tabs
      v-else
      v-model="activeTab"
      class="on-photo-tabs"
      :tabs="[
        { value: 'overview', label: 'Overview' },
        { value: 'config', label: 'Config' },
        { value: 'network', label: 'Network' },
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
          <Card class="col-span-2" data-test="package-about-card">
            <div class="eyebrow mb-1">About</div>
            <h3 class="mb-3">What this is</h3>
            <p v-if="aboutBody" class="text-sm text-foreground whitespace-pre-line">{{ aboutBody }}</p>
            <p v-else class="text-sm text-muted-foreground">No description yet.</p>

            <details v-if="ownerNotes" class="mt-5 border-t border-border pt-4" data-test="package-owner-notes">
              <summary class="text-sm text-muted-foreground cursor-pointer select-none">
                Setup notes for the owner · technical
              </summary>
              <div class="mt-3">
                <MarkdownBlock :source="ownerNotes" />
              </div>
            </details>
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

            <p v-if="updateUnknown && update.lastCheckedAt" class="text-sm text-muted-foreground mb-3">
              Aurora couldn't reach the image registry when it last looked, so this is the version
              on the box rather than the newest one available.
            </p>
            <p v-else-if="updateUnknown" class="text-sm text-muted-foreground mb-3">
              Aurora hasn't checked for a newer version yet, so this is the version on the box.
              It checks on its own schedule, or you can use Check and update above.
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
              <!-- Three honest states, not two. "Aurora couldn't reach the
                   registry last time it looked" printed directly above
                   "Checked never." — two sentences contradicting each other
                   inside one card, on a box that had simply never looked. -->
              <template v-if="update.lastCheckedAt">Checked {{ checkedLabel(update.lastCheckedAt) }}.</template>
              <template v-else>Not checked yet.</template>
              <template v-if="update.lastUpdatedAt"> Last updated {{ checkedLabel(update.lastUpdatedAt) }}.</template>
            </p>
          </Card>

          <!-- Consolidated Status/Structure/vhosts/Ports/Depends-on/Requirements
               into one card. Version, Limits and Backup stay separate — each
               has its own state or action a plain row can't carry. -->
          <Card class="col-span-2" data-test="package-details-card">
            <div class="eyebrow mb-1">Details</div>
            <h3 class="mb-3">At a glance</h3>
            <dl class="text-sm space-y-3">
              <div class="flex items-center justify-between">
                <dt class="text-muted-foreground">Status</dt>
                <!-- Reads the same derived light as the badge at the top of
                     the page (lib/packageLifecycle.ts::deriveStatusLight),
                     not a raw enabled/running boolean — this is exactly
                     where "Status: stopped" and a NOT INSTALLED badge used
                     to disagree, because this row bypassed the light
                     entirely. It also now shows starting/unhealthy rather
                     than collapsing them into a flat "running". -->
                <dd class="font-mono">{{ lightState }}</dd>
              </div>
              <div class="flex items-center justify-between">
                <dt class="text-muted-foreground">Docker</dt>
                <dd><DockerBadge :structure="structure" /></dd>
              </div>
              <div class="flex items-start justify-between gap-4">
                <dt class="text-muted-foreground shrink-0">vhosts</dt>
                <dd class="font-mono text-xs text-right">
                  <template v-if="(detail.vhosts ?? []).length">
                    <div v-for="v in detail.vhosts" :key="v">{{ v }}</div>
                  </template>
                  <span v-else class="text-muted-foreground font-sans">none</span>
                </dd>
              </div>
              <div class="flex items-start justify-between gap-4">
                <dt class="text-muted-foreground shrink-0">Ports</dt>
                <dd class="font-mono text-xs text-right">
                  <template v-if="(detail.ports ?? []).length">
                    <div v-for="(p, i) in detail.ports" :key="i">{{ portLabel(p) }}</div>
                  </template>
                  <span v-else class="text-muted-foreground font-sans">none</span>
                </dd>
              </div>
              <div class="flex items-start justify-between gap-4">
                <dt class="text-muted-foreground shrink-0">Depends on</dt>
                <dd>
                  <div class="flex gap-2 flex-wrap justify-end">
                    <span v-for="d in (detail.dependsOn ?? [])" :key="d" class="font-mono text-xs px-2 py-1 rounded border border-border">{{ d }}</span>
                    <span v-if="!(detail.dependsOn ?? []).length" class="text-muted-foreground">none</span>
                  </div>
                </dd>
              </div>
              <template v-if="minRamMb !== undefined || minDiskGb !== undefined">
                <div v-if="minRamMb !== undefined" class="flex justify-between">
                  <dt class="text-muted-foreground">Minimum RAM</dt>
                  <dd class="font-mono">{{ minRamMb }} MB</dd>
                </div>
                <div v-if="minDiskGb !== undefined" class="flex justify-between">
                  <dt class="text-muted-foreground">Minimum disk</dt>
                  <dd class="font-mono">{{ minDiskGb }} GB</dd>
                </div>
              </template>
            </dl>
          </Card>
          <!-- The manifest's backup: block, read-only. Writing it is a
               manifest job; this is here so an operator can see at a
               glance whether this app's data is covered, and whether the
               copy would actually restore. -->
          <Card v-if="detail.backup" data-test="package-backup-card">
            <div class="eyebrow mb-1">Backup</div>
            <h3 class="mb-3">What gets protected</h3>
            <ul class="text-sm font-mono text-foreground space-y-0.5 mb-3">
              <li v-for="p in detail.backup.paths" :key="p">{{ p }}</li>
              <li v-if="!detail.backup.paths.length" class="text-muted-foreground font-sans">nothing declared</li>
            </ul>
            <template v-if="detail.backup.before.length">
              <div class="eyebrow mb-1">Before each snapshot</div>
              <ul class="text-xs text-muted-foreground space-y-1">
                <li v-for="a in detail.backup.before" :key="a.description">
                  {{ a.description }}<span v-if="a.container" class="font-mono"> ({{ a.container }})</span>
                </li>
              </ul>
            </template>
            <p v-else-if="detail.backup.paths.length" class="text-xs text-destructive" data-test="package-backup-warning">
              Nothing runs before the snapshot. If this app keeps a database in that path, the copy
              is being taken while it is being written to, and may not restore.
            </p>
            <router-link to="/backup" class="text-xs text-muted-foreground no-underline hover:underline mt-3 inline-block">
              Backup →
            </router-link>
          </Card>

          <!-- Ceilings against live usage. One runaway container on a
               box with no swap takes everything down with it. Reads the
               manifest default + any override regardless of install
               state, so no gate is needed here — PackagePreview shows
               the same card pre-install. -->
          <PackageResourcesCard :package="detail.name" />
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
              <p v-else-if="cleanEnvHelp(spec.comment)" class="text-xs text-muted-foreground mt-1">{{ cleanEnvHelp(spec.comment) }}</p>
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

      <!-- ── Network ─────────────────────────────────────────────── -->
      <div v-else-if="activeTab === 'network'">
        <Card v-if="networkErr" class="p-6" data-state="error" role="alert">
          <Alert variant="destructive"><AlertDescription>{{ networkErr }}</AlertDescription></Alert>
          <Button size="sm" variant="secondary" class="mt-3" @click="loadNetwork">Try again</Button>
        </Card>

        <div v-else-if="!network" class="space-y-3" data-state="loading">
          <Skeleton class="h-32 w-full" />
        </div>

        <Card v-else class="p-6" data-test="package-network-card">
          <div class="flex items-start justify-between gap-6 mb-5">
            <div>
              <div class="eyebrow mb-1">Outbound</div>
              <div class="flex items-center gap-2 mb-2">
                <h3>{{ egressLabel(network) }}</h3>
                <Badge :tone="egressTone(network)">{{ network.mode }}</Badge>
              </div>
              <p class="text-sm text-muted-foreground max-w-xl">
                <template v-if="tunnelled">
                  <template v-if="network.egressIp">
                    Traffic from this app leaves through the VPN gateway. The outside world sees
                    <span class="font-mono">{{ network.egressIp }}</span>
                    <template v-if="network.egressCountry"> in {{ network.egressCountry }}</template>,
                    not your home connection.
                  </template>
                  <template v-else>
                    Traffic from this app leaves through the VPN gateway, not your home connection.
                  </template>
                </template>
                <template v-else>
                  <template v-if="network.egressIp">
                    Traffic from this app leaves over your normal connection, from
                    <span class="font-mono">{{ network.egressIp }}</span
                    ><template v-if="network.egressCountry"> in {{ network.egressCountry }}</template>.
                  </template>
                  <template v-else>
                    Traffic from this app leaves over your normal connection, same as everything else on the box.
                  </template>
                </template>
              </p>
            </div>

            <Button
              v-if="!network.locked"
              size="sm"
              :variant="tunnelled ? 'secondary' : 'primary'"
              :disabled="actionsLocked"
              data-test="egress-toggle"
              @click="tunnelConfirm = true"
            >{{ tunnelled ? 'Stop tunnelling' : 'Send through the VPN' }}</Button>
          </div>

          <Alert v-if="network.locked" variant="info" data-test="egress-locked">
            <AlertDescription>{{ network.lockedReason }}</AlertDescription>
          </Alert>

          <Alert v-else-if="tunnelled && !network.gatewayHealthy" variant="destructive">
            <AlertDescription>
              The gateway is down, which means this app has no network at all right now. That is
              the kill switch doing its job — nothing is leaking out over your own connection —
              but the app will not work until the tunnel is back.
            </AlertDescription>
          </Alert>

          <dl v-else class="text-sm space-y-2 border-t border-border pt-4">
            <div class="flex justify-between">
              <dt class="text-muted-foreground">Gateway</dt>
              <dd class="font-mono">{{ network.gateway ?? '—' }}</dd>
            </div>
            <div class="flex justify-between">
              <dt class="text-muted-foreground">Containers affected</dt>
              <dd class="font-mono text-xs">{{ network.containers.join(', ') }}</dd>
            </div>
            <div v-if="network.publishedPorts.length" class="flex justify-between">
              <dt class="text-muted-foreground">Published ports</dt>
              <dd class="font-mono text-xs">{{ network.publishedPorts.join(', ') }}</dd>
            </div>
          </dl>
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

    <!-- Install confirm: what this will actually do to the box, before you
         agree to it rather than after. -->
    <Dialog :open="addConfirm" @update:open="addConfirm = $event">
      <template #title>Install {{ heading }}?</template>
      <template #description>Here is what that involves.</template>
      <PackageImpactPanel v-if="detail" :detail="detail" />
      <template #footer>
        <Button variant="secondary" @click="addConfirm = false">Cancel</Button>
        <Button data-test="confirm-install" @click="confirmAdd">Install it</Button>
      </template>
    </Dialog>

    <!-- Egress change confirm. Not destructive, but it restarts the app,
         moves its ports and changes what it can reach, so the switch
         states its consequences rather than being a toggle you can knock
         with an elbow. -->
    <Dialog :open="tunnelConfirm" @update:open="tunnelConfirm = $event">
      <template #title>
        {{ tunnelled ? `Stop tunnelling ${heading}?` : `Send ${heading} through the VPN?` }}
      </template>
      <template #description>
        <span>Here is what changes:</span>
      </template>
      <ul class="list-disc pl-5 space-y-1.5 text-sm" data-test="egress-consequences">
        <li v-for="line in consequences" :key="line">{{ line }}</li>
      </ul>
      <template #footer>
        <Button variant="secondary" @click="tunnelConfirm = false">Cancel</Button>
        <Button data-test="egress-confirm" @click="confirmEgressChange">
          {{ tunnelled ? 'Stop tunnelling' : 'Tunnel it' }}
        </Button>
      </template>
    </Dialog>

    <!-- Uninstall confirm — destructive, so it gets a dialog rather than a
         one-click button (same pattern as VpnView's rotate-key confirm). -->
    <Dialog :open="removeOpen" @update:open="removeOpen = $event">
      <template #title>Uninstall {{ heading }}?</template>
      <template #description>
        This stops and removes its containers. Its data stays on disk unless you also
        clear its volumes by hand.
      </template>
      <template #footer>
        <Button variant="secondary" @click="removeOpen = false">Cancel</Button>
        <Button variant="danger" :disabled="busy === 'uninstall'" data-test="confirm-uninstall" @click="confirmDisable">
          {{ busy === 'uninstall' ? 'Uninstalling…' : 'Uninstall' }}
        </Button>
      </template>
    </Dialog>
  </section>
</template>
