<script setup lang="ts">
/**
 * Backup. See docs/BACKUP_PAGE_DESIGN.md.
 *
 * Aurora has shipped Kopia since early on and said nothing about it, so
 * checking whether your data was safe meant logging into a second web UI
 * on port 51515. In practice nobody did, and a backup nobody looks at is
 * one that quietly stopped working in March.
 *
 * This page answers one question — is my data safe, and how do I get it
 * back — and leaves repository management and the snapshot browser to
 * Kopia, which already does them well.
 */
import { computed, onMounted, ref } from 'vue';

import {
  BackupApi,
  backupHeadline,
  backupPageState,
  backupTone,
  daysSinceLastRun,
  dedupSavingPct,
  sourcesAtRisk,
  type BackupPolicy,
  type BackupSource,
  type BackupStatus,
  type Snapshot,
  type SnapshotState,
} from '@/api/backup';
import { humanBytes, relTime } from '@/lib/utils';
import { humanCopyForError } from '@/lib/http-error-copy';
import { packageLabel } from '@/lib/packageName';
import { toast } from '@/composables/useToast';
import { usePackagesStore } from '@/stores/packages';
import Card from '@/components/ui/Card.vue';
import Badge from '@/components/ui/Badge.vue';
import Tabs from '@/components/ui/Tabs.vue';
import JobLogPanel from '@/components/JobLogPanel.vue';
import {
  Alert,
  AlertDescription,
  Button,
  Dialog,
  Input,
  Label,
  Select,
  Skeleton,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui';

type Tab = 'overview' | 'protected' | 'restore' | 'schedule';

const packages = usePackagesStore();

const status = ref<BackupStatus | null>(null);
const sources = ref<BackupSource[]>([]);
const snapshots = ref<Snapshot[]>([]);
const policy = ref<BackupPolicy | null>(null);
const loadErr = ref<string | null>(null);
const loading = ref(true);
const tab = ref<Tab>('overview');

const jobId = ref<string | null>(null);
const jobLabel = ref<string | undefined>(undefined);

async function load(): Promise<void> {
  loading.value = true;
  loadErr.value = null;
  try {
    const [s, src, snaps, pol] = await Promise.all([
      BackupApi.status(),
      BackupApi.sources(),
      BackupApi.snapshots(),
      BackupApi.policy(),
    ]);
    status.value = s;
    sources.value = src;
    snapshots.value = snaps;
    policy.value = pol;
  } catch (e) {
    loadErr.value = humanCopyForError(e, { subject: 'your backups', action: 'load' });
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  void load();
  if (!packages.list.length) void packages.fetchList().catch(() => { /* labels fall back to the raw name */ });
});

// ── Derived page state ────────────────────────────────────────────────
const pageState = computed(() =>
  status.value && policy.value ? backupPageState(status.value, policy.value) : null,
);
const days = computed(() => (status.value ? daysSinceLastRun(status.value) : null));
const headline = computed(() => (pageState.value ? backupHeadline(pageState.value, days.value) : ''));
const tone = computed(() => (pageState.value ? backupTone(pageState.value) : 'neutral'));
const atRisk = computed(() => sourcesAtRisk(sources.value));
const saving = computed(() => (status.value ? dedupSavingPct(status.value) : null));

const enabledSources = computed(() => sources.value.filter((s) => s.enabled));

/** Recent runs, newest first — the five most recent snapshots overall. */
const recentRuns = computed(() => snapshots.value.slice(0, 5));

function sourceLabel(s: BackupSource): string {
  if (!s.package) return s.path;
  const pkg = packages.list.find((p) => p.name === s.package);
  return pkg ? packageLabel(pkg) : s.package;
}

function snapshotStateTone(s: SnapshotState | null): 'ok' | 'warn' | 'err' | 'neutral' {
  if (s === 'ok') return 'ok';
  if (s === 'partial') return 'warn';
  if (s === 'failed') return 'err';
  return 'neutral';
}

function durationLabel(ms: number | null): string {
  if (ms === null || !Number.isFinite(ms)) return '—';
  const total = Math.round(ms / 1000);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return m > 0 ? `${m}m ${s}s` : `${s}s`;
}

function whenLabel(iso: string | null): string {
  return iso ? relTime(iso) : 'never';
}

// ── Back up now ───────────────────────────────────────────────────────
const startingRun = ref(false);

async function runNow(): Promise<void> {
  startingRun.value = true;
  try {
    const { jobId: id } = await BackupApi.runNow();
    jobLabel.value = undefined;
    jobId.value = id;
  } catch (e) {
    toast({
      title: "Couldn't start the backup",
      description: humanCopyForError(e, { subject: 'a backup', action: 'start' }),
      variant: 'destructive',
    });
  } finally {
    startingRun.value = false;
  }
}

// ── Restore ───────────────────────────────────────────────────────────
const restoreSourceId = ref<string>('');
const restoreTarget = ref<Snapshot | null>(null);

const restoreOptions = computed(() =>
  enabledSources.value.map((s) => ({ value: s.id, label: `${sourceLabel(s)} — ${s.path}` })),
);

const restorableSnapshots = computed(() =>
  snapshots.value.filter((s) => s.sourceId === restoreSourceId.value && s.state !== 'failed'),
);

const restoreTargetSource = computed(() =>
  sources.value.find((s) => s.id === restoreTarget.value?.sourceId) ?? null,
);

async function confirmRestore(): Promise<void> {
  const snap = restoreTarget.value;
  if (!snap) return;
  try {
    const { jobId: id } = await BackupApi.restore(snap.id);
    jobLabel.value = `Restoring ${snap.path}`;
    jobId.value = id;
    tab.value = 'overview';
  } catch (e) {
    toast({
      title: "Couldn't start the restore",
      description: humanCopyForError(e, { subject: 'this snapshot', action: 'restore' }),
      variant: 'destructive',
    });
  } finally {
    restoreTarget.value = null;
  }
}

// ── Source toggle ─────────────────────────────────────────────────────
const togglingId = ref<string | null>(null);

async function toggleSource(source: BackupSource): Promise<void> {
  togglingId.value = source.id;
  try {
    const updated = await BackupApi.setSourceEnabled(source.id, !source.enabled);
    const i = sources.value.findIndex((s) => s.id === updated.id);
    if (i >= 0) sources.value[i] = updated;
  } catch (e) {
    toast({
      title: "Couldn't change that",
      description: humanCopyForError(e, { subject: 'this source', action: 'update' }),
      variant: 'destructive',
    });
  } finally {
    togglingId.value = null;
  }
}

// ── Schedule form ─────────────────────────────────────────────────────
const form = ref<BackupPolicy | null>(null);
const savingPolicy = ref(false);

const policyDirty = computed(
  () => form.value !== null && policy.value !== null && JSON.stringify(form.value) !== JSON.stringify(policy.value),
);

function editPolicy(): void {
  form.value = policy.value ? { ...policy.value } : null;
}

async function savePolicy(): Promise<void> {
  if (!form.value) return;
  savingPolicy.value = true;
  try {
    policy.value = await BackupApi.savePolicy(form.value);
    form.value = { ...policy.value };
    toast({ title: 'Saved', description: 'The backup schedule was updated.', variant: 'success', duration: 3000 });
  } catch (e) {
    toast({
      title: "Couldn't save",
      description: humanCopyForError(e, { subject: 'the backup schedule', action: 'save' }),
      variant: 'destructive',
    });
  } finally {
    savingPolicy.value = false;
  }
}

async function onJobDone(): Promise<void> {
  await load();
  editPolicy();
}

function onTabChange(next: Tab): void {
  tab.value = next;
  if (next === 'schedule' && !form.value) editPolicy();
  if (next === 'restore' && !restoreSourceId.value && enabledSources.value.length) {
    restoreSourceId.value = enabledSources.value[0].id;
  }
}
</script>

<template>
  <section>
    <div class="mb-8 on-photo flex items-start justify-between gap-6">
      <div>
        <div class="eyebrow mb-2">Data</div>
        <h1>Backup</h1>
        <p class="max-w-2xl mt-2">
          What Aurora is protecting, when it last ran, and how to get it back.
        </p>
      </div>
      <Button
        v-if="!loading && !loadErr && pageState !== 'not-configured'"
        :disabled="startingRun || jobId !== null"
        data-test="backup-run-now"
        @click="runNow"
      >{{ startingRun ? 'Starting…' : 'Back up now' }}</Button>
    </div>

    <!-- loading -->
    <div v-if="loading" class="grid grid-cols-4 gap-4" data-state="loading">
      <Card v-for="n in 4" :key="`sk-${n}`">
        <Skeleton class="h-3 w-16 mb-2" />
        <Skeleton class="h-5 w-24 mb-3" />
        <Skeleton class="h-8 w-32" />
      </Card>
    </div>

    <!-- error -->
    <Card v-else-if="loadErr" class="p-6" data-state="error" role="alert">
      <Alert variant="destructive"><AlertDescription>{{ loadErr }}</AlertDescription></Alert>
      <Button size="sm" variant="secondary" class="mt-3" @click="load">Try again</Button>
    </Card>

    <!-- no repository yet -->
    <Card v-else-if="pageState === 'not-configured'" class="p-10 text-center" data-state="empty">
      <h3 class="mb-2">No backup repository yet</h3>
      <p class="text-sm text-muted-foreground max-w-xl mx-auto mb-4">
        Kopia is installed but has nowhere to put anything. Create a repository in Kopia's own
        interface — a folder on a second drive is fine to start with, and you can point it at
        Backblaze or S3 later without changing anything here.
      </p>
      <a href="https://backup.aurora.local/" target="_blank" rel="noopener noreferrer" class="text-sm">
        Open Kopia ↗
      </a>
    </Card>

    <template v-else>
      <!-- The one thing worth interrupting for. -->
      <Card
        v-if="pageState === 'failed' || pageState === 'stale' || pageState === 'unreachable'"
        class="p-5 mb-6"
        data-test="backup-alarm"
        role="alert"
      >
        <div class="flex items-start gap-3">
          <Badge :tone="tone">{{ pageState }}</Badge>
          <div>
            <p class="font-medium mb-1">{{ headline }}</p>
            <p class="text-sm text-muted-foreground">
              <template v-if="pageState === 'failed'">
                The run that failed is in the history below. Nothing older was lost; the last
                good snapshot is still there.
              </template>
              <template v-else-if="pageState === 'unreachable'">
                The figures below are the last ones Aurora saw, not live ones. Check the
                repository is mounted or reachable.
              </template>
              <template v-else>
                A schedule that stops quietly is how data actually gets lost. Run one now, then
                check the schedule tab.
              </template>
            </p>
          </div>
        </div>
      </Card>

      <JobLogPanel
        v-if="jobId"
        :job-id="jobId"
        :label="jobLabel"
        dismissible
        class="mb-6"
        @success="onJobDone"
        @failed="onJobDone"
        @retry="runNow"
        @dismiss="jobId = null"
      />

      <Tabs
        :model-value="tab"
        class="on-photo-tabs mb-6"
        :tabs="[
          { value: 'overview', label: 'Overview' },
          { value: 'protected', label: `What's protected`, hint: String(enabledSources.length) },
          { value: 'restore', label: 'Restore' },
          { value: 'schedule', label: 'Schedule' },
        ]"
        @update:model-value="onTabChange($event as Tab)"
      />

      <!-- ── Overview ────────────────────────────────────────────── -->
      <div v-if="tab === 'overview' && status">
        <div class="grid grid-cols-4 gap-4 mb-6">
          <Card data-test="backup-last-run">
            <div class="eyebrow mb-1">Last run</div>
            <h3 class="card-title mb-2">{{ whenLabel(status.lastRunAt) }}</h3>
            <div class="flex items-center gap-2">
              <Badge :tone="snapshotStateTone(status.lastRunState)">{{ status.lastRunState ?? 'never' }}</Badge>
              <span class="text-xs text-muted-foreground">took {{ durationLabel(status.lastRunDurationMs) }}</span>
            </div>
          </Card>

          <Card>
            <div class="eyebrow mb-1">Repository</div>
            <h3 class="card-title mb-2">{{ status.repoKind ?? '—' }}</h3>
            <p class="text-xs text-muted-foreground font-mono truncate">{{ status.repoLocation ?? '—' }}</p>
            <Badge :tone="status.encrypted ? 'ok' : 'warn'" class="mt-2">
              {{ status.encrypted ? 'encrypted' : 'not encrypted' }}
            </Badge>
          </Card>

          <Card data-test="backup-size">
            <div class="eyebrow mb-1">Size</div>
            <h3 class="card-title mb-2">{{ humanBytes(status.uniqueSizeBytes) }}</h3>
            <p class="text-xs text-muted-foreground">
              of {{ humanBytes(status.totalSizeBytes) }} protected<template v-if="saving !== null">,
              {{ saving }}% saved by deduplication</template>.
            </p>
          </Card>

          <Card>
            <div class="eyebrow mb-1">Next run</div>
            <h3 class="card-title mb-2">{{ status.nextRunAt ? whenLabel(status.nextRunAt).replace(' ago', '') : '—' }}</h3>
            <p class="text-xs text-muted-foreground">
              {{ policy?.scheduleLabel ?? '—' }} · {{ status.snapshotCount }} snapshots kept
            </p>
          </Card>
        </div>

        <Card class="p-0 overflow-hidden">
          <div class="px-5 pt-5">
            <div class="eyebrow mb-3">Recent runs</div>
          </div>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead class="pl-5">What</TableHead>
                <TableHead>When</TableHead>
                <TableHead>Result</TableHead>
                <TableHead class="text-right pr-5">Size</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              <TableRow v-for="run in recentRuns" :key="run.id">
                <TableCell class="pl-5 font-mono text-xs">{{ run.path }}</TableCell>
                <TableCell class="text-muted-foreground">{{ relTime(run.createdAt) }}</TableCell>
                <TableCell>
                  <Badge :tone="snapshotStateTone(run.state)">{{ run.state }}</Badge>
                </TableCell>
                <TableCell class="text-right pr-5 font-mono">{{ humanBytes(run.sizeBytes) }}</TableCell>
              </TableRow>
            </TableBody>
          </Table>
        </Card>
      </div>

      <!-- ── What's protected ────────────────────────────────────── -->
      <div v-else-if="tab === 'protected'">
        <Card
          v-if="atRisk.length"
          class="p-5 mb-4"
          data-test="backup-risk-warning"
          role="alert"
        >
          <Alert variant="destructive">
            <AlertDescription>
              {{ atRisk.length }} source{{ atRisk.length === 1 ? '' : 's' }} would not restore
              cleanly. A database copied while it is running is a corrupted file with a timestamp
              on it, not a backup.
            </AlertDescription>
          </Alert>
        </Card>

        <Card class="p-0 overflow-hidden">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead class="pl-5">Source</TableHead>
                <TableHead>Before each snapshot</TableHead>
                <TableHead>Last snapshot</TableHead>
                <TableHead class="text-right">Size</TableHead>
                <TableHead class="text-right pr-5">On</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              <TableRow
                v-for="source in sources"
                :key="source.id"
                :data-source="source.id"
                :class="source.enabled ? '' : 'opacity-55'"
              >
                <TableCell class="pl-5">
                  <div class="font-medium">{{ sourceLabel(source) }}</div>
                  <div class="text-xs text-muted-foreground font-mono">{{ source.path }}</div>
                </TableCell>

                <TableCell>
                  <ul v-if="source.beforeActions.length" class="space-y-1">
                    <li v-for="a in source.beforeActions" :key="a.description" class="text-xs">
                      {{ a.description }}
                      <span v-if="a.container" class="text-muted-foreground font-mono">({{ a.container }})</span>
                    </li>
                  </ul>
                  <span
                    v-else-if="source.needsConsistencyAction"
                    class="text-xs text-destructive"
                    data-test="backup-needs-action"
                  >Nothing — and this holds a database, so the copy may not restore.</span>
                  <span v-else class="text-xs text-muted-foreground">Nothing needed.</span>
                </TableCell>

                <TableCell>
                  <div class="flex items-center gap-2">
                    <Badge :tone="snapshotStateTone(source.lastSnapshotState)">
                      {{ source.lastSnapshotState ?? 'never' }}
                    </Badge>
                    <span class="text-xs text-muted-foreground">{{ whenLabel(source.lastSnapshotAt) }}</span>
                  </div>
                </TableCell>

                <TableCell class="text-right font-mono text-xs">
                  {{ humanBytes(source.sizeBytes) }}
                  <div v-if="source.fileCount !== null" class="text-muted-foreground">
                    {{ source.fileCount.toLocaleString() }} files
                  </div>
                </TableCell>

                <TableCell class="text-right pr-5">
                  <Button
                    size="sm"
                    :variant="source.enabled ? 'danger' : 'secondary'"
                    :disabled="togglingId === source.id"
                    :data-test="`backup-toggle-${source.id}`"
                    @click="toggleSource(source)"
                  >{{ source.enabled ? 'Turn off' : 'Turn on' }}</Button>
                </TableCell>
              </TableRow>
            </TableBody>
          </Table>
        </Card>

        <p class="text-xs text-muted-foreground mt-4">
          Paths come from each app's manifest. Adding one that Aurora doesn't manage is Kopia's
          job, not this page's.
        </p>
      </div>

      <!-- ── Restore ─────────────────────────────────────────────── -->
      <div v-else-if="tab === 'restore'">
        <Card class="p-5 mb-4">
          <Label for="restore-source">Restore from</Label>
          <Select
            id="restore-source"
            v-model="restoreSourceId"
            :options="restoreOptions"
            aria-label="Source to restore"
            class="max-w-xl"
          />
          <p class="text-xs text-muted-foreground mt-2">
            Restores the whole source back where it came from. To pull out a single file, or put
            one somewhere else, use Kopia directly.
          </p>
        </Card>

        <Card
          v-if="!restorableSnapshots.length"
          class="p-10 text-center text-sm text-muted-foreground"
          data-state="empty"
        >
          No snapshots to restore for this source yet.
        </Card>

        <Card v-else class="p-0 overflow-hidden">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead class="pl-5">Taken</TableHead>
                <TableHead>Result</TableHead>
                <TableHead>Consistent</TableHead>
                <TableHead class="text-right">Size</TableHead>
                <TableHead class="text-right pr-5"></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              <TableRow v-for="snap in restorableSnapshots" :key="snap.id" :data-snapshot="snap.id">
                <TableCell class="pl-5">{{ relTime(snap.createdAt) }}</TableCell>
                <TableCell><Badge :tone="snapshotStateTone(snap.state)">{{ snap.state }}</Badge></TableCell>
                <TableCell>
                  <Badge :tone="snap.consistent ? 'ok' : 'warn'">
                    {{ snap.consistent ? 'yes' : 'unverified' }}
                  </Badge>
                </TableCell>
                <TableCell class="text-right font-mono text-xs">{{ humanBytes(snap.sizeBytes) }}</TableCell>
                <TableCell class="text-right pr-5">
                  <Button size="sm" variant="secondary" @click="restoreTarget = snap">Restore</Button>
                </TableCell>
              </TableRow>
            </TableBody>
          </Table>
        </Card>
      </div>

      <!-- ── Schedule ────────────────────────────────────────────── -->
      <div v-else-if="tab === 'schedule' && form">
        <Card class="p-6 max-w-2xl">
          <form class="space-y-5" @submit.prevent="savePolicy">
            <div>
              <Label for="cron" hint="cron">When to run</Label>
              <Input id="cron" v-model="form.scheduleCron" class="font-mono" />
              <p class="text-xs text-muted-foreground mt-1">Currently: {{ policy?.scheduleLabel }}</p>
            </div>

            <div class="grid grid-cols-3 gap-4">
              <div>
                <Label for="keep-daily">Keep daily</Label>
                <Input id="keep-daily" v-model.number="form.keepDaily" type="number" min="0" />
              </div>
              <div>
                <Label for="keep-weekly">Keep weekly</Label>
                <Input id="keep-weekly" v-model.number="form.keepWeekly" type="number" min="0" />
              </div>
              <div>
                <Label for="keep-monthly">Keep monthly</Label>
                <Input id="keep-monthly" v-model.number="form.keepMonthly" type="number" min="0" />
              </div>
            </div>

            <div>
              <Label for="stale-days" hint="days">Warn me after</Label>
              <Input id="stale-days" v-model.number="form.stalenessWarnDays" type="number" min="1" class="max-w-32" />
              <p class="text-xs text-muted-foreground mt-1">
                How long without a successful backup before Aurora starts saying so, here and on
                the Overview page.
              </p>
            </div>

            <div class="flex items-center gap-3 pt-1">
              <Button type="submit" :disabled="!policyDirty || savingPolicy" data-test="backup-policy-save">
                {{ savingPolicy ? 'Saving…' : 'Save' }}
              </Button>
              <Button type="button" variant="secondary" :disabled="!policyDirty || savingPolicy" @click="editPolicy">
                Cancel
              </Button>
            </div>
          </form>
        </Card>
      </div>
    </template>

    <!-- Restore confirm. Destructive, so it gets a dialog and the danger
         variant, same as removing an app. -->
    <Dialog :open="restoreTarget !== null" @update:open="restoreTarget = $event ? restoreTarget : null">
      <template #title>Restore this snapshot?</template>
      <template #description>
        <span v-if="restoreTarget">
          This overwrites everything currently in
          <span class="font-mono">{{ restoreTarget.path }}</span>
          with the copy taken {{ relTime(restoreTarget.createdAt) }}. Anything changed since then
          is lost.
          <template v-if="restoreTargetSource && !restoreTarget.consistent">
            This snapshot was taken without a consistency step, so a database inside it may not
            come back cleanly.
          </template>
        </span>
      </template>
      <template #footer>
        <Button variant="secondary" @click="restoreTarget = null">Cancel</Button>
        <Button variant="danger" data-test="backup-confirm-restore" @click="confirmRestore">Restore</Button>
      </template>
    </Dialog>
  </section>
</template>
