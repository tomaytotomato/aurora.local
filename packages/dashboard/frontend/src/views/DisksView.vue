<script setup lang="ts">
/**
 * Disks. See docs/DISKS_PAGE_DESIGN.md.
 *
 * The host roles for mergerfs, SnapRAID and smartd all landed before any
 * of this was visible, which is the wrong way round: a drive gives weeks
 * of warning before it dies, and you only get that warning if something
 * is looking at it.
 *
 * Read-only, plus the two SnapRAID operations that are safe to run at any
 * time. Formatting and pool membership stay in group_vars and Ansible on
 * purpose.
 */
import { computed, onMounted, ref } from 'vue';

import {
  DisksApi,
  branchUsedPct,
  daysSinceSync,
  diskAttention,
  diskTone,
  disksNeedingAttention,
  disksPageState,
  fullBranches,
  parityFreshness,
  parityHeadline,
  parityTone,
  poolUsedPct,
  protocolLabel,
  sortByHealth,
  type Disk,
  type DiskSmart,
  type NetworkStorageDevice,
  type Parity,
  type Pool,
} from '@/api/disks';
import { humanBytes, relTime } from '@/lib/utils';
import { humanCopyForError } from '@/lib/http-error-copy';
import { toast } from '@/composables/useToast';
import Card from '@/components/ui/Card.vue';
import Badge from '@/components/ui/Badge.vue';
import Tabs from '@/components/ui/Tabs.vue';
import Progress from '@/components/ui/Progress.vue';
import JobLogPanel from '@/components/JobLogPanel.vue';
import {
  Alert,
  AlertDescription,
  Button,
  Dialog,
  Skeleton,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui';

type Tab = 'overview' | 'drives' | 'network' | 'parity';

const disks = ref<Disk[]>([]);
const pool = ref<Pool | null>(null);
const parity = ref<Parity | null>(null);
const loadErr = ref<string | null>(null);
const loading = ref(true);
const tab = ref<Tab>('overview');

const jobId = ref<string | null>(null);
const lastAction = ref<'sync' | 'scrub' | null>(null);

// ── Storage on the network ────────────────────────────────────────────
// Loaded separately from the disks above: it takes a few seconds to listen
// for mDNS announcements, and a NAS being slow to answer must not hold up
// the page that shows this box's own drives.
const network = ref<NetworkStorageDevice[] | null>(null);
const networkLoading = ref(false);
const networkErr = ref<string | null>(null);

async function loadNetwork(): Promise<void> {
  networkLoading.value = true;
  networkErr.value = null;
  try {
    network.value = await DisksApi.network();
  } catch (e) {
    networkErr.value = humanCopyForError(e, { subject: 'your network', action: 'search' });
  } finally {
    networkLoading.value = false;
  }
}

async function load(): Promise<void> {
  loading.value = true;
  loadErr.value = null;
  try {
    const [d, p, par] = await Promise.all([DisksApi.list(), DisksApi.pool(), DisksApi.parity()]);
    disks.value = d;
    pool.value = p;
    parity.value = par;
  } catch (e) {
    loadErr.value = humanCopyForError(e, { subject: 'your disks', action: 'load' });
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  void load();
  void loadNetwork();
});

// ── Derived ───────────────────────────────────────────────────────────
const pageState = computed(() =>
  pool.value && parity.value ? disksPageState(disks.value, pool.value, parity.value) : null,
);
const attention = computed(() => disksNeedingAttention(disks.value));
const sorted = computed(() => sortByHealth(disks.value));
const usedPct = computed(() => (pool.value ? poolUsedPct(pool.value) : null));
const full = computed(() => (pool.value ? fullBranches(pool.value) : []));
const freshness = computed(() => (parity.value ? parityFreshness(parity.value) : null));
const parityLine = computed(() => (parity.value ? parityHeadline(parity.value) : ''));
const syncDays = computed(() => (parity.value ? daysSinceSync(parity.value) : null));

const worstHealthLabel = computed(() => {
  if (disks.value.some((d) => d.health === 'failing')) return 'a drive is failing';
  if (attention.value.length) return `${attention.value.length} need${attention.value.length === 1 ? 's' : ''} a look`;
  return 'all passing';
});

function diskById(id: string): Disk | undefined {
  return disks.value.find((d) => d.id === id);
}

function branchLabel(diskId: string): string {
  const d = diskById(diskId);
  return d ? `${d.model ?? d.device}` : diskId;
}

function whenLabel(iso: string | null): string {
  return iso ? relTime(iso) : 'never';
}

/** Bar tone: amber once a branch is close to mergerfs's floor. */
function branchTone(pct: number): string {
  if (pct >= 98) return 'text-destructive';
  if (pct >= 90) return 'text-warning';
  return 'text-muted-foreground';
}

// ── SMART detail ──────────────────────────────────────────────────────
const smartOpen = ref<Disk | null>(null);
const smart = ref<DiskSmart | null>(null);
const smartErr = ref<string | null>(null);

async function openSmart(disk: Disk): Promise<void> {
  smartOpen.value = disk;
  smart.value = null;
  smartErr.value = null;
  try {
    smart.value = await DisksApi.smart(disk.id);
  } catch (e) {
    smartErr.value = humanCopyForError(e, { subject: 'this drive’s SMART data', action: 'load' });
  }
}

// ── Parity actions ────────────────────────────────────────────────────
const starting = ref(false);

async function runParity(action: 'sync' | 'scrub'): Promise<void> {
  starting.value = true;
  lastAction.value = action;
  try {
    const { jobId: id } = action === 'sync' ? await DisksApi.sync() : await DisksApi.scrub();
    jobId.value = id;
  } catch (e) {
    toast({
      title: action === 'sync' ? "Couldn't start the sync" : "Couldn't start the check",
      description: humanCopyForError(e, { subject: 'parity', action: 'start' }),
      variant: 'destructive',
    });
  } finally {
    starting.value = false;
  }
}

async function onJobDone(): Promise<void> {
  await load();
}

function retryJob(): void {
  const action = lastAction.value;
  jobId.value = null;
  if (action) void runParity(action);
}
</script>

<template>
  <section>
    <div class="mb-8 on-photo flex items-start justify-between gap-6">
      <div>
        <div class="eyebrow mb-2">Storage</div>
        <h1>Disks</h1>
        <p class="max-w-2xl mt-2">
          Drive health, how much room is left, and whether parity could actually rebuild a
          failed disk.
        </p>
      </div>
      <div v-if="parity?.configured" class="flex items-center gap-2">
        <Button
          variant="secondary"
          :disabled="starting || jobId !== null"
          data-test="parity-scrub"
          @click="runParity('scrub')"
        >Check parity</Button>
        <Button
          :disabled="starting || jobId !== null"
          data-test="parity-sync"
          @click="runParity('sync')"
        >Sync parity now</Button>
      </div>
    </div>

    <div v-if="loading" class="grid grid-cols-3 gap-4" data-state="loading">
      <Card v-for="n in 3" :key="`sk-${n}`">
        <Skeleton class="h-3 w-16 mb-2" />
        <Skeleton class="h-6 w-32 mb-3" />
        <Skeleton class="h-4 w-40" />
      </Card>
    </div>

    <Card v-else-if="loadErr" class="p-6" data-state="error" role="alert">
      <Alert variant="destructive"><AlertDescription>{{ loadErr }}</AlertDescription></Alert>
      <Button size="sm" variant="secondary" class="mt-3" @click="load">Try again</Button>
    </Card>

    <template v-else-if="pool && parity">
      <!-- The reason someone opens this page in a hurry. -->
      <Card
        v-if="pageState === 'failing' || pageState === 'attention'"
        class="p-5 mb-6"
        role="alert"
        data-test="disks-alarm"
      >
        <div class="flex items-start gap-3">
          <Badge :tone="pageState === 'failing' ? 'err' : 'warn'">{{ pageState }}</Badge>
          <div class="space-y-1">
            <p v-for="d in attention" :key="d.id" class="text-sm">
              <span class="font-mono">{{ d.device }}</span>
              <span class="text-muted-foreground"> ({{ d.model ?? 'unknown model' }}) — </span>
              {{ diskAttention(d) }}
            </p>
            <p v-if="freshness && freshness !== 'fresh' && freshness !== 'not-configured'" class="text-sm">
              {{ parityLine }}
            </p>
            <p v-for="b in full" :key="b.path" class="text-sm">
              <span class="font-mono">{{ b.path }}</span>
              <span class="text-muted-foreground">
                — under mergerfs's {{ humanBytes(pool.minFreeBytes) }} floor, so nothing new is
                being written to it.
              </span>
            </p>
          </div>
        </div>
      </Card>

      <JobLogPanel
        v-if="jobId"
        :job-id="jobId"
        dismissible
        class="mb-6"
        @success="onJobDone"
        @failed="onJobDone"
        @retry="retryJob"
        @dismiss="jobId = null"
      />

      <Tabs
        :model-value="tab"
        class="on-photo-tabs mb-6"
        :tabs="[
          { value: 'overview', label: 'Overview' },
          { value: 'drives', label: 'Drives', hint: String(disks.length) },
          { value: 'network', label: 'On your network', hint: network === null ? undefined : String(network.length) },
          { value: 'parity', label: 'Parity' },
        ]"
        @update:model-value="tab = $event as Tab"
      />

      <!-- ── Overview ────────────────────────────────────────────── -->
      <div v-if="tab === 'overview'">
        <Card v-if="pool.configured" class="mb-4" data-test="pool-capacity">
          <div class="eyebrow mb-1">Pool</div>
          <div class="flex items-baseline gap-3 mb-1">
            <h3 class="card-title">{{ humanBytes(pool.usedBytes) }} of {{ humanBytes(pool.totalBytes) }}</h3>
            <span v-if="usedPct !== null" class="text-sm text-muted-foreground tabular-nums">{{ usedPct }}%</span>
          </div>
          <p class="text-xs text-muted-foreground mb-4">
            <span class="font-mono">{{ pool.mountpoint }}</span> ·
            {{ pool.branches.length }} disks unioned by mergerfs
            <template v-if="pool.createPolicy"> · new files go to the disk with the most free space</template>
          </p>
          <Progress :value="usedPct ?? 0" class="mb-5" />

          <!-- The whole point: a pool percentage hides a single full disk. -->
          <div class="eyebrow mb-2">Per disk</div>
          <ul class="space-y-3">
            <li v-for="b in pool.branches" :key="b.path" :data-branch="b.path">
              <div class="flex items-baseline justify-between text-sm mb-1">
                <span>
                  <span class="font-mono text-xs">{{ b.path }}</span>
                  <span class="text-muted-foreground"> · {{ branchLabel(b.diskId) }}</span>
                </span>
                <span class="tabular-nums" :class="branchTone(branchUsedPct(b))">
                  {{ humanBytes(b.usedBytes) }} / {{ humanBytes(b.totalBytes) }}
                  ({{ branchUsedPct(b) }}%)
                </span>
              </div>
              <Progress :value="branchUsedPct(b)" />
            </li>
          </ul>
        </Card>

        <Card v-else class="p-10 text-center mb-4" data-state="empty">
          <h3 class="mb-2">One disk, no pool</h3>
          <p class="text-sm text-muted-foreground max-w-lg mx-auto">
            There is no mergerfs pool on this box, which is a perfectly reasonable way to run
            one. Drive health is still watched, and the Drives tab has it.
          </p>
        </Card>

        <div class="grid grid-cols-3 gap-4">
          <Card>
            <div class="eyebrow mb-1">Drives</div>
            <h3 class="card-title mb-2">{{ disks.length }}</h3>
            <p class="text-xs text-muted-foreground">{{ worstHealthLabel }}</p>
          </Card>

          <Card data-test="parity-card">
            <div class="eyebrow mb-1">Parity</div>
            <div class="flex items-center gap-2 mb-2">
              <Badge v-if="freshness" :tone="parityTone(freshness)">{{ freshness }}</Badge>
            </div>
            <p class="text-xs text-muted-foreground">{{ parityLine }}</p>
          </Card>

          <Card>
            <div class="eyebrow mb-1">Unprotected</div>
            <h3 class="card-title mb-2">
              {{ parity.pendingChanges === null ? '—' : parity.pendingChanges.toLocaleString() }}
            </h3>
            <p class="text-xs text-muted-foreground">
              files changed since the last sync. Parity covers the array as it was then, not as
              it is now.
            </p>
          </Card>
        </div>
      </div>

      <!-- ── Drives ──────────────────────────────────────────────── -->
      <div v-else-if="tab === 'drives'">
        <Card class="p-0 overflow-hidden">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead class="pl-5">Drive</TableHead>
                <TableHead>Role</TableHead>
                <TableHead>Health</TableHead>
                <TableHead class="text-right">Used</TableHead>
                <TableHead class="text-right">Temp</TableHead>
                <TableHead class="text-right">Powered on</TableHead>
                <TableHead class="text-right pr-5">Reallocated</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              <TableRow
                v-for="d in sorted"
                :key="d.id"
                :data-disk="d.device"
                class="cursor-pointer"
                @click="openSmart(d)"
              >
                <TableCell class="pl-5">
                  <div class="font-mono text-sm">{{ d.device }}</div>
                  <div class="text-xs text-muted-foreground">
                    {{ d.model ?? 'unknown model' }} · {{ humanBytes(d.sizeBytes) }}
                    <template v-if="d.mountpoint"> · {{ d.mountpoint }}</template>
                  </div>
                </TableCell>

                <TableCell><Badge tone="neutral">{{ d.role }}</Badge></TableCell>

                <TableCell>
                  <Badge :tone="diskTone(d)">{{ d.health }}</Badge>
                  <div v-if="diskAttention(d)" class="text-xs text-warning mt-1" data-test="disk-attention">
                    {{ diskAttention(d) }}
                  </div>
                </TableCell>

                <TableCell class="text-right font-mono text-xs">
                  <template v-if="d.usedBytes !== null">
                    {{ humanBytes(d.usedBytes) }}
                  </template>
                  <span v-else class="text-muted-foreground">—</span>
                </TableCell>

                <TableCell class="text-right font-mono text-xs">
                  {{ d.temperatureC === null ? '—' : `${d.temperatureC}°C` }}
                </TableCell>

                <TableCell class="text-right font-mono text-xs">
                  <template v-if="d.powerOnHours !== null">
                    {{ Math.round(d.powerOnHours / 24 / 365 * 10) / 10 }} yr
                  </template>
                  <span v-else class="text-muted-foreground">—</span>
                </TableCell>

                <TableCell class="text-right pr-5 font-mono text-xs">
                  <span :class="(d.reallocatedSectors ?? 0) > 0 ? 'text-warning' : ''">
                    {{ d.reallocatedSectors === null ? '—' : d.reallocatedSectors }}
                  </span>
                </TableCell>
              </TableRow>
            </TableBody>
          </Table>
        </Card>

        <p class="text-xs text-muted-foreground mt-4">
          A drive reporting <span class="font-mono">passed</span> with reallocated sectors above
          zero is still a drive on its way out — SMART's overall verdict is set by the
          manufacturer and errs generous. Select a row for the full attribute table.
        </p>
      </div>

      <!-- ── On your network ─────────────────────────────────────── -->
      <div v-else-if="tab === 'network'">
        <div class="flex items-baseline justify-between gap-4 mb-4">
          <p class="text-sm text-muted-foreground max-w-2xl">
            Storage on your network that is not this box — a NAS, another computer, or
            anything sharing a folder. Aurora finds these by listening for the
            announcements they already make; it does not go looking through your network.
          </p>
          <Button variant="secondary" size="sm" :disabled="networkLoading"
                  data-test="network-rescan" @click="loadNetwork">
            {{ networkLoading ? 'Looking…' : 'Look again' }}
          </Button>
        </div>

        <Alert v-if="networkErr" variant="destructive" class="mb-4">
          <AlertDescription>{{ networkErr }}</AlertDescription>
        </Alert>

        <div v-else-if="networkLoading && network === null" class="space-y-2" data-state="loading">
          <Skeleton class="h-20 w-full" />
          <Skeleton class="h-20 w-full" />
        </div>

        <!-- Most homes have nothing here, so this is the state most people
             see: it has to read as a normal answer, not a failure. -->
        <Card v-else-if="network && network.length === 0" class="p-10 text-center" data-state="empty">
          <h3 class="mb-2">Nothing is sharing storage on your network</h3>
          <p class="text-sm text-muted-foreground max-w-lg mx-auto">
            That is perfectly normal — this box holds its own files quite happily.
            If you do have a NAS and it is not here, it may have network discovery
            turned off, or be on a different part of your network.
          </p>
        </Card>

        <div v-else-if="network" class="space-y-3">
          <Card v-for="device in network" :key="device.address" class="p-6" data-test="network-device">
            <div class="flex items-start justify-between gap-4">
              <div class="min-w-0">
                <div class="flex items-center gap-2 mb-1">
                  <h3 class="card-title">{{ device.name }}</h3>
                  <Badge v-if="!device.reachable" tone="warn" data-test="device-asleep">not answering</Badge>
                </div>
                <p class="text-sm text-muted-foreground">
                  {{ device.model ? device.model + ' · ' : '' }}{{ device.host ?? device.address }}
                </p>
                <p class="text-xs text-muted-foreground mt-2">
                  Shares over {{ device.protocols.map((p) => protocolLabel(p.kind)).join(', ') }}
                </p>
              </div>
              <div class="text-right shrink-0">
                <code class="font-mono text-xs text-muted-foreground">{{ device.address }}</code>
              </div>
            </div>

            <!-- Honest about what Aurora can and cannot do with it yet.
                 A button that did nothing would be worse than this line. -->
            <p class="text-xs text-muted-foreground mt-4 pt-4 border-t border-border">
              <template v-if="device.reachable">
                Aurora can see it. Using its space from this box — for films, photos or
                backups — is coming next.
              </template>
              <template v-else>
                It is advertising itself but not answering right now, which usually means
                it is asleep or switched off.
              </template>
            </p>
          </Card>
        </div>
      </div>

      <!-- ── Parity ──────────────────────────────────────────────── -->
      <div v-else>
        <Card v-if="!parity.configured" class="p-10 text-center" data-state="empty">
          <h3 class="mb-2">No parity protection</h3>
          <p class="text-sm text-muted-foreground max-w-lg mx-auto">
            Right now, if a drive fails you lose whatever was on it. Add a spare drive —
            at least as large as your biggest one — and Aurora can survive one drive
            failing. Nothing here needs doing until you have that spare.
          </p>
        </Card>

        <template v-else>
          <!-- The deletion guard: a good guard rail and a terrible silent
               failure, so it gets said in plain words. -->
          <Card v-if="freshness === 'aborted'" class="p-5 mb-4" role="alert" data-test="parity-aborted">
            <Alert variant="destructive">
              <AlertDescription>
                The scheduled sync stopped itself on purpose.
                {{ parity.deletedSinceSync }} files had been deleted since the last one, which is
                over the {{ parity.deletionThreshold }} it is willing to accept without being
                asked. That guard exists so a large accidental deletion cannot
                quietly destroy your ability to recover from it. If the deletions were
                deliberate, sync now and parity will catch up.
              </AlertDescription>
            </Alert>
          </Card>

          <div class="grid grid-cols-2 gap-4 mb-4">
            <Card>
              <div class="eyebrow mb-1">Last sync</div>
              <h3 class="card-title mb-2">{{ whenLabel(parity.lastSyncAt) }}</h3>
              <div class="flex items-center gap-2">
                <Badge v-if="freshness" :tone="parityTone(freshness)">{{ parity.lastSyncState }}</Badge>
                <span v-if="syncDays !== null" class="text-xs text-muted-foreground">
                  warns after {{ parity.stalenessWarnDays }} days
                </span>
              </div>
            </Card>
            <Card>
              <div class="eyebrow mb-1">Last check</div>
              <h3 class="card-title mb-2">{{ whenLabel(parity.lastScrubAt) }}</h3>
              <p class="text-xs text-muted-foreground">
                A check reads existing parity back against the data. It finds bit rot; a sync
                does not.
              </p>
            </Card>
          </div>

          <Card>
            <div class="eyebrow mb-3">Since the last sync</div>
            <dl class="text-sm space-y-2">
              <div class="flex justify-between">
                <dt class="text-muted-foreground">Files added or changed</dt>
                <dd class="font-mono">{{ parity.pendingChanges?.toLocaleString() ?? '—' }}</dd>
              </div>
              <div class="flex justify-between">
                <dt class="text-muted-foreground">Files deleted</dt>
                <dd class="font-mono">
                  {{ parity.deletedSinceSync ?? '—' }}
                  <span class="text-muted-foreground">/ {{ parity.deletionThreshold ?? '—' }} allowed</span>
                </dd>
              </div>
              <div class="flex justify-between">
                <dt class="text-muted-foreground">Parity disk</dt>
                <dd class="font-mono text-xs">
                  {{ parity.parityDiskIds.map((id) => diskById(id)?.device ?? id).join(', ') }}
                </dd>
              </div>
            </dl>
            <p class="text-xs text-muted-foreground mt-4">
              SnapRAID is snapshot parity, not RAID. It can rebuild a failed disk back to the
              state of the last sync — anything written since then is not covered.
            </p>
          </Card>
        </template>
      </div>
    </template>

    <!-- Full SMART table for one drive. -->
    <Dialog :open="smartOpen !== null" @update:open="smartOpen = $event ? smartOpen : null">
      <template #title>{{ smartOpen?.device }} — SMART</template>
      <template #description>
        <span v-if="smartOpen" class="text-xs">
          {{ smartOpen.model ?? 'unknown model' }}
          <template v-if="smartOpen.serial"> · serial {{ smartOpen.serial }}</template>
        </span>
      </template>

      <div v-if="smartErr" role="alert" class="text-sm">
        <Alert variant="destructive"><AlertDescription>{{ smartErr }}</AlertDescription></Alert>
      </div>
      <div v-else-if="!smart" class="space-y-2" data-state="loading">
        <Skeleton class="h-4 w-full" />
        <Skeleton class="h-4 w-full" />
        <Skeleton class="h-4 w-2/3" />
      </div>
      <p v-else-if="!smart.supported" class="text-sm text-muted-foreground">
        This drive doesn't report SMART data — usually a USB enclosure that doesn't pass it
        through. Aurora can see the drive but can't tell you anything about its health, which is
        why it reads "unknown" rather than "passed".
      </p>
      <Table v-else>
        <TableHeader>
          <TableRow>
            <TableHead>#</TableHead>
            <TableHead>Attribute</TableHead>
            <TableHead class="text-right">Value</TableHead>
            <TableHead class="text-right">Worst</TableHead>
            <TableHead class="text-right">Threshold</TableHead>
            <TableHead class="text-right">Raw</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          <TableRow v-for="a in smart.attributes" :key="a.id">
            <TableCell class="font-mono text-xs text-muted-foreground">{{ a.id }}</TableCell>
            <TableCell class="font-mono text-xs">{{ a.name }}</TableCell>
            <TableCell class="text-right font-mono text-xs">{{ a.value }}</TableCell>
            <TableCell class="text-right font-mono text-xs">{{ a.worst }}</TableCell>
            <TableCell class="text-right font-mono text-xs">{{ a.threshold }}</TableCell>
            <TableCell class="text-right font-mono text-xs">{{ a.raw }}</TableCell>
          </TableRow>
        </TableBody>
      </Table>

      <template #footer>
        <Button variant="secondary" @click="smartOpen = null">Close</Button>
      </template>
    </Dialog>
  </section>
</template>
