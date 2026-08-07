<script setup lang="ts">
// Aurora's inbound WireGuard server — remote access INTO the LAN.
// See docs/VPN_PAGE_DESIGN.md for the full rationale. This is not the
// privacy package's outbound Gluetun tunnel; the header says so.
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import {
  VpnApi,
  peerOnline,
  type OpenVpnConfig,
  type VpnConfig,
  type VpnPeer,
  type VpnPeerSecret,
  type VpnStatus,
} from '@/api/vpn';
import { humanCopyForError } from '@/lib/http-error-copy';
import { humanBytes } from '@/lib/utils';
import { toast } from '@/composables/useToast';
import Card from '@/components/ui/Card.vue';
import Button from '@/components/ui/Button.vue';
import {
  Alert,
  AlertDescription,
  Badge,
  Checkbox,
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
  Tabs,
} from '@/components/ui';

type PageState = 'loading' | 'error' | 'not-configured' | 'ready';

const pageState = ref<PageState>('loading');
const loadErr = ref<string | null>(null);
const status = ref<VpnStatus | null>(null);
const config = ref<VpnConfig | null>(null);
const peers = ref<VpnPeer[]>([]);
const activeTab = ref<'overview' | 'peers' | 'advanced'>('overview');

let poll: ReturnType<typeof setInterval> | null = null;

async function refreshStatus(): Promise<void> {
  try {
    status.value = await VpnApi.status();
  } catch {
    /* keep last snapshot; the load path owns the error state */
  }
}

async function load(): Promise<void> {
  pageState.value = 'loading';
  loadErr.value = null;
  try {
    status.value = await VpnApi.status();
    try {
      config.value = await VpnApi.config();
    } catch {
      // 404 before first init — the not-configured empty state.
      config.value = null;
      pageState.value = 'not-configured';
      return;
    }
    peers.value = await VpnApi.peers();
    syncForm();
    pageState.value = 'ready';
  } catch (e) {
    loadErr.value = humanCopyForError(e, { subject: 'the VPN service', action: 'reach' });
    pageState.value = 'error';
  }
}

onMounted(async () => {
  await load();
  poll = setInterval(refreshStatus, 5_000);
});
onBeforeUnmount(() => {
  if (poll) clearInterval(poll);
});

const badge = computed(() => {
  const s = status.value?.runState;
  if (s === 'running' && status.value?.reachable === false) return { tone: 'warn' as const, text: 'degraded' };
  if (s === 'running') return { tone: 'ok' as const, text: 'running' };
  if (s === 'stopped') return { tone: 'neutral' as const, text: 'stopped' };
  if (s === 'degraded') return { tone: 'warn' as const, text: 'degraded' };
  return { tone: 'neutral' as const, text: 'off' };
});

const reachableLabel = computed(() => {
  const r = status.value?.reachable;
  if (r === true) return 'yes';
  if (r === false) return 'no, check port forwarding';
  return 'checking…';
});

// ---- not-configured → first-run init ----
const initing = ref(false);
async function generateConfig(): Promise<void> {
  initing.value = true;
  try {
    config.value = await VpnApi.initConfig();
    peers.value = await VpnApi.peers();
    syncForm();
    pageState.value = 'ready';
  } catch (e) {
    toast({ title: 'Setup failed', description: humanCopyForError(e, { subject: 'the server config', action: 'generate' }), variant: 'destructive' });
  } finally {
    initing.value = false;
  }
}

// ---- server config form ----
const form = ref<VpnConfig | null>(null);
const saving = ref(false);
function syncForm(): void {
  form.value = config.value ? { ...config.value } : null;
}
const dirty = computed(() =>
  !!form.value && !!config.value && JSON.stringify(form.value) !== JSON.stringify(config.value),
);
async function saveConfig(): Promise<void> {
  if (!form.value) return;
  saving.value = true;
  try {
    config.value = await VpnApi.saveConfig(form.value);
    syncForm();
    toast({ title: 'Saved', description: 'Server configuration updated.', variant: 'success', duration: 3000 });
  } catch (e) {
    toast({ title: "Couldn't save", description: humanCopyForError(e, { subject: 'the config', action: 'save' }), variant: 'destructive' });
  } finally {
    saving.value = false;
  }
}

// ---- rotate server key (destructive) ----
const rotateOpen = ref(false);
const rotating = ref(false);
async function rotateKey(): Promise<void> {
  rotating.value = true;
  try {
    config.value = await VpnApi.rotateServerKey();
    syncForm();
    toast({ title: 'Server key rotated', description: 'Every peer config must be re-downloaded.', variant: 'warning', duration: 5000 });
  } catch (e) {
    toast({ title: "Couldn't rotate the server key", description: humanCopyForError(e, { subject: 'the server key', action: 'rotate' }), variant: 'destructive' });
  } finally {
    rotating.value = false;
    rotateOpen.value = false;
  }
}

// ---- peers ----
const onlineCount = computed(() => peers.value.filter((p) => peerOnline(p)).length);

async function refreshPeers(): Promise<void> {
  try {
    peers.value = await VpnApi.peers();
  } catch (e) {
    toast({ title: "Couldn't refresh peers", description: humanCopyForError(e, { subject: 'the peer list', action: 'refresh' }), variant: 'destructive' });
  }
}

async function togglePeer(p: VpnPeer): Promise<void> {
  try {
    await VpnApi.togglePeer(p.id);
    await refreshPeers();
  } catch (e) {
    toast({ title: "Couldn't update peer", description: humanCopyForError(e, { subject: 'the peer', action: 'update' }), variant: 'destructive' });
  }
}

// remove confirm
const removeTarget = ref<VpnPeer | null>(null);
const removing = ref(false);
async function confirmRemove(): Promise<void> {
  if (!removeTarget.value) return;
  removing.value = true;
  try {
    await VpnApi.removePeer(removeTarget.value.id);
    await refreshPeers();
  } catch (e) {
    toast({ title: "Couldn't remove the peer", description: humanCopyForError(e, { subject: 'the peer', action: 'remove' }), variant: 'destructive' });
  } finally {
    removing.value = false;
    removeTarget.value = null;
  }
}

// QR dialog (re-scan an existing peer)
const qrPeer = ref<VpnPeer | null>(null);
const qrUrl = computed(() => (qrPeer.value ? VpnApi.peerQrCodeUrl(qrPeer.value.id) : ''));

// add-peer dialog
const addOpen = ref(false);
const addName = ref('');
const tunnelMode = ref<'split' | 'full'>('split');
const adding = ref(false);
const secret = ref<VpnPeerSecret | null>(null); // one-time reveal
const showRawConf = ref(false);

function openAdd(): void {
  addName.value = '';
  tunnelMode.value = 'split';
  secret.value = null;
  showRawConf.value = false;
  addOpen.value = true;
}
async function submitAdd(): Promise<void> {
  adding.value = true;
  try {
    secret.value = await VpnApi.addPeer(addName.value.trim() || 'New device', tunnelMode.value);
    await refreshPeers();
  } catch (e) {
    toast({ title: "Couldn't add peer", description: humanCopyForError(e, { subject: 'the peer', action: 'add' }), variant: 'destructive' });
  } finally {
    adding.value = false;
  }
}
function closeAdd(): void {
  addOpen.value = false;
  secret.value = null;
}
const secretQrSrc = computed(() => (secret.value ? `data:image/png;base64,${secret.value.qrPngBase64}` : ''));
function downloadSecretConf(): void {
  if (!secret.value) return;
  const blob = new Blob([secret.value.confText], { type: 'text/plain' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `${secret.value.peer.name.replace(/\W+/g, '-').toLowerCase()}.conf`;
  a.click();
  URL.revokeObjectURL(url);
}

function handshakeLabel(p: VpnPeer): string {
  if (!p.lastHandshakeAt) return 'never';
  const d = new Date(p.lastHandshakeAt).getTime();
  if (Number.isNaN(d)) return '—';
  const mins = Math.round((Date.now() - d) / 60_000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins} min ago`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `${hrs} h ago`;
  return `${Math.round(hrs / 24)} d ago`;
}

// ---- advanced / OpenVPN ----
const openVpn = ref<OpenVpnConfig | null>(null);
const openVpnLoaded = ref(false);
async function loadOpenVpn(): Promise<void> {
  if (openVpnLoaded.value) return;
  try {
    openVpn.value = await VpnApi.openVpnConfig();
    openVpnLoaded.value = true;
  } catch { /* silent — advanced tab is optional */ }
}
async function toggleOpenVpn(on: boolean): Promise<void> {
  try {
    openVpn.value = await VpnApi.saveOpenVpnConfig({ enabled: on });
  } catch (e) {
    toast({ title: "Couldn't update OpenVPN", description: humanCopyForError(e, { subject: 'the OpenVPN setting', action: 'update' }), variant: 'destructive' });
  }
}
function onTab(t: string): void {
  activeTab.value = t as typeof activeTab.value;
  if (t === 'advanced') void loadOpenVpn();
}
</script>

<template>
  <section data-view="vpn">
    <!-- Header — always visible, states render below it. -->
    <div class="mb-10 on-photo">
      <div class="flex items-center gap-3">
        <div class="eyebrow mb-2">Network</div>
      </div>
      <div class="flex items-baseline gap-3">
        <h1>VPN</h1>
        <Badge v-if="pageState === 'ready'" :tone="badge.tone" class="bg-card" data-test="vpn-status-badge">{{ badge.text }}</Badge>
      </div>
      <p class="max-w-2xl mt-2">
        Remote access into aurora.local from anywhere, over WireGuard. This is not the
        outbound VPN that anonymises the media stack; for that, see
        <router-link to="/apps/privacy" class="text-white underline decoration-white/40 hover:decoration-white">Privacy → Gluetun</router-link>.
      </p>
    </div>

    <!-- loading -->
    <div v-if="pageState === 'loading'" class="grid grid-cols-2 gap-4" data-state="loading">
      <Card v-for="n in 2" :key="`skeleton-${n}`" class="h-40 p-6 space-y-3">
        <Skeleton class="h-3 w-20" />
        <Skeleton class="h-6 w-40" />
        <Skeleton class="h-4 w-full" />
        <Skeleton class="h-4 w-2/3" />
      </Card>
    </div>

    <!--
      error — Alert's tint is only a few percent opacity by design (a
      light wash over a card, not a solid banner), so on its own over
      the photo it barely shows. Card gives it a real backing.
    -->
    <Card v-else-if="pageState === 'error'" class="p-8">
      <Alert variant="destructive" class="mb-4"><AlertDescription>{{ loadErr }}</AlertDescription></Alert>
      <Button variant="secondary" size="sm" @click="load">Try again</Button>
    </Card>

    <!-- not-configured -->
    <Card v-else-if="pageState === 'not-configured'" data-state="empty" class="p-10 text-center" data-test="vpn-not-configured">
      <svg viewBox="0 0 24 24" class="w-8 h-8 text-muted-foreground mx-auto mb-4" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
        <path d="M12 3l8 3v6c0 5-4 8-8 9-4-1-8-4-8-9V6z" stroke-linecap="round" stroke-linejoin="round" />
        <path d="M12 8v4M12 15.5v.5" stroke-linecap="round" />
      </svg>
      <h3 class="card-title mb-2">Generate your server configuration</h3>
      <p class="text-sm text-muted-foreground max-w-md mx-auto mb-6">
        Aurora will generate a WireGuard keypair, pick a free UDP port (default 51820),
        and prefill sensible defaults from what it already knows about this box. You can
        tune everything afterwards.
      </p>
      <Button :disabled="initing" data-test="vpn-generate" @click="generateConfig">
        {{ initing ? 'Generating…' : 'Generate configuration' }}
      </Button>
    </Card>

    <!--
      ready. The tabbed region sits over the app-wide aurora photo. The
      tab strip stays transparent and takes on-photo-tabs for legible
      triggers (same as PackagesList's filter bar); the content panels
      below are opaque Cards. An opaque box around the tabs reads as a
      floating panel detached from the cards under it, so we don't use one.
    -->
    <Tabs
      v-else
      :model-value="activeTab"
      class="on-photo-tabs"
      :tabs="[
        { value: 'overview', label: 'Overview' },
        { value: 'peers', label: 'Peers' },
        { value: 'advanced', label: 'Advanced' },
      ]"
      @update:model-value="onTab"
    >
      <!-- OVERVIEW -->
      <div v-if="activeTab === 'overview'" class="grid grid-cols-2 gap-4">
        <Card class="p-8" data-card="vpn-tunnel">
          <h3 class="card-title mb-1">Tunnel</h3>
          <p class="card-subtitle mb-4">Live status</p>
          <div class="text-3xl font-mono text-foreground mb-4">{{ badge.text }}</div>
          <dl class="text-sm space-y-2">
            <div class="flex justify-between"><dt class="text-muted-foreground">Interface</dt><dd class="font-mono">{{ status?.interface ?? '—' }}</dd></div>
            <div class="flex justify-between"><dt class="text-muted-foreground">Listen port</dt><dd class="font-mono">{{ status?.listenPort ?? '—' }}</dd></div>
            <div class="flex justify-between"><dt class="text-muted-foreground">Peers online</dt><dd class="font-mono">{{ status?.peersOnline ?? 0 }} / {{ status?.peersTotal ?? 0 }}</dd></div>
            <div class="flex justify-between"><dt class="text-muted-foreground">Reachable from outside</dt><dd class="font-mono">{{ reachableLabel }}</dd></div>
          </dl>
          <Alert v-if="status?.reachable === false" variant="warning" class="mt-4">
            <AlertDescription>
              Aurora can't confirm this is reachable from outside your LAN. Forward UDP
              port {{ status?.listenPort ?? 51820 }} to this box on your router, then refresh.
            </AlertDescription>
          </Alert>
        </Card>

        <Card v-if="form" class="p-8" data-card="vpn-config">
          <h3 class="card-title mb-1">Server</h3>
          <p class="card-subtitle mb-4">Configuration</p>
          <div class="space-y-3">
            <div>
              <Label for="vpn-endpoint">Endpoint (hostname or public IP)</Label>
              <Input id="vpn-endpoint" v-model="form.endpointHost" placeholder="aurora.duckdns.org" />
              <p class="text-xs text-muted-foreground mt-1">A dynamic-DNS hostname is more reliable than a raw IP on a home connection.</p>
            </div>
            <div class="grid grid-cols-2 gap-3">
              <div>
                <Label for="vpn-port">Listen port</Label>
                <Input id="vpn-port" v-model.number="form.listenPort" type="number" />
              </div>
              <div>
                <Label for="vpn-mtu">MTU</Label>
                <Input id="vpn-mtu" v-model.number="form.mtu" type="number" />
              </div>
            </div>
            <div>
              <Label for="vpn-dns">DNS pushed to peers</Label>
              <Input id="vpn-dns" v-model="form.dns" />
            </div>
            <div>
              <Label for="vpn-subnet">Server tunnel address</Label>
              <Input id="vpn-subnet" v-model="form.serverAddress" />
            </div>
          </div>
          <div class="mt-5">
            <Button :disabled="!dirty || saving" data-test="vpn-save" @click="saveConfig">
              {{ saving ? 'Saving…' : 'Save' }}
            </Button>
          </div>
          <hr class="my-5" />
          <button
            type="button"
            class="text-sm text-muted-foreground hover:text-foreground"
            data-test="vpn-rotate-open"
            @click="rotateOpen = true"
          >Regenerate server key</button>
        </Card>
      </div>

      <!--
        PEERS — one Card wraps header + body (empty or table) rather than
        leaving the header row and table bare on the page: over the
        photo background neither would have any opaque surface behind
        their text. Same "one Card per feature area" shape as Settings'
        LAN-aliases card.
      -->
      <div v-else-if="activeTab === 'peers'">
        <Card class="p-8" data-card="vpn-peers">
          <div class="flex items-center justify-between mb-4">
            <div class="flex items-center gap-3">
              <h3 class="card-title">Peers</h3>
              <Badge tone="ok">{{ onlineCount }} online</Badge>
            </div>
            <Button size="sm" data-test="vpn-add-peer" @click="openAdd">Add peer</Button>
          </div>

          <div v-if="peers.length === 0" data-state="empty" class="text-center py-10">
            <p class="text-sm text-foreground mb-1">No devices yet.</p>
            <p class="text-xs text-muted-foreground">Add your phone or laptop to reach aurora.local from anywhere.</p>
          </div>

          <Table v-else data-test="vpn-peers" class="text-sm">
            <TableHeader>
              <TableRow class="hover:bg-transparent">
                <TableHead>Name</TableHead>
                <TableHead class="w-24">Status</TableHead>
                <TableHead>Allowed IPs</TableHead>
                <TableHead class="w-32">Data</TableHead>
                <TableHead class="w-28">Handshake</TableHead>
                <TableHead class="w-56"></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              <TableRow v-for="p in peers" :key="p.id" :data-peer="p.id">
                <TableCell class="align-baseline">
                  <div class="text-foreground">{{ p.name }}</div>
                  <Badge v-if="p.killSwitch" tone="warn" class="mt-1">full tunnel · kill switch</Badge>
                </TableCell>
                <TableCell class="align-baseline">
                  <Badge :tone="peerOnline(p) ? 'ok' : 'neutral'">{{ peerOnline(p) ? 'online' : (p.enabled ? 'idle' : 'off') }}</Badge>
                </TableCell>
                <TableCell class="align-baseline font-mono text-xs text-muted-foreground">{{ p.allowedIps }}</TableCell>
                <TableCell class="align-baseline font-mono text-xs text-muted-foreground">↓{{ humanBytes(p.rxBytes) }} ↑{{ humanBytes(p.txBytes) }}</TableCell>
                <TableCell class="align-baseline text-xs text-muted-foreground">{{ handshakeLabel(p) }}</TableCell>
                <TableCell class="align-baseline text-right whitespace-nowrap">
                  <button type="button" class="text-xs text-foreground hover:underline mr-3" @click="qrPeer = p">QR</button>
                  <a :href="VpnApi.peerConfigUrl(p.id)" download class="text-xs text-foreground hover:underline mr-3">Download</a>
                  <button type="button" class="text-xs text-muted-foreground hover:text-foreground mr-3" @click="togglePeer(p)">{{ p.enabled ? 'Suspend' : 'Resume' }}</button>
                  <button type="button" class="text-xs text-muted-foreground hover:text-destructive" data-test="vpn-remove-peer" @click="removeTarget = p">Remove</button>
                </TableCell>
              </TableRow>
            </TableBody>
          </Table>
        </Card>
      </div>

      <!--
        ADVANCED — Alert + form live inside one Card too, for the same
        reason as Peers above: the Alert's tint is only a few percent
        opacity, so on its own it's just a faint smudge over the photo,
        not a readable banner.
      -->
      <div v-else>
        <Card class="p-8" data-card="vpn-advanced">
          <Alert variant="info" class="mb-4">
            <AlertDescription>
              WireGuard is faster, simpler, and the recommended option above. Only turn this
              on if you have a device that can't run a WireGuard client.
            </AlertDescription>
          </Alert>
          <div v-if="openVpn">
            <div class="flex items-center gap-2 mb-4">
              <Checkbox id="ovpn-enabled" :model-value="openVpn.enabled" @update:model-value="toggleOpenVpn" />
              <Label for="ovpn-enabled" class="mb-0">Also run an OpenVPN server</Label>
            </div>
            <div v-if="openVpn.enabled" class="grid grid-cols-2 gap-3 max-w-md">
              <div>
                <Label for="ovpn-port">Port</Label>
                <Input id="ovpn-port" v-model.number="openVpn.port" type="number" />
              </div>
              <div>
                <Label for="ovpn-proto">Protocol</Label>
                <Select
                  id="ovpn-proto"
                  :model-value="openVpn.protocol"
                  :options="[{ value: 'udp', label: 'UDP' }, { value: 'tcp', label: 'TCP' }]"
                  @update:model-value="openVpn.protocol = $event as 'udp' | 'tcp'"
                />
              </div>
            </div>
          </div>
        </Card>
      </div>
    </Tabs>

    <!-- Rotate-key confirm -->
    <Dialog :open="rotateOpen" @update:open="rotateOpen = $event">
      <template #title>Regenerate server key?</template>
      <template #description>Every existing peer's config becomes invalid and must be re-downloaded. This cannot be undone.</template>
      <template #footer>
        <Button variant="secondary" @click="rotateOpen = false">Cancel</Button>
        <Button :disabled="rotating" @click="rotateKey">{{ rotating ? 'Rotating…' : 'Regenerate' }}</Button>
      </template>
    </Dialog>

    <!-- Remove-peer confirm -->
    <Dialog :open="!!removeTarget" @update:open="(v) => { if (!v) removeTarget = null; }">
      <template #title>Remove {{ removeTarget?.name }}?</template>
      <template #description>That device will lose access immediately. You can add it again later, but it'll need a fresh config.</template>
      <template #footer>
        <Button variant="secondary" @click="removeTarget = null">Cancel</Button>
        <Button :disabled="removing" @click="confirmRemove">{{ removing ? 'Removing…' : 'Remove' }}</Button>
      </template>
    </Dialog>

    <!-- QR re-scan -->
    <Dialog :open="!!qrPeer" @update:open="(v) => { if (!v) qrPeer = null; }">
      <template #title>{{ qrPeer?.name }}</template>
      <template #description>Scan with the WireGuard app on the device.</template>
      <div class="flex justify-center py-2">
        <img v-if="qrUrl" :src="qrUrl" alt="WireGuard peer QR code" class="w-48 h-48 rounded bg-white p-2" />
      </div>
      <template #footer>
        <Button variant="secondary" @click="qrPeer = null">Close</Button>
      </template>
    </Dialog>

    <!-- Add peer / one-time reveal -->
    <Dialog :open="addOpen" :dismissable="!secret" @update:open="(v) => { if (!v) closeAdd(); }">
      <template #title>{{ secret ? 'Peer added' : 'Add a peer' }}</template>

      <!-- form -->
      <div v-if="!secret" class="space-y-4">
        <div>
          <Label for="peer-name">Name</Label>
          <Input id="peer-name" v-model="addName" placeholder="Bruce's phone" />
        </div>
        <div class="space-y-2">
          <button type="button" class="flex items-start gap-2 text-left w-full" @click="tunnelMode = 'split'">
            <Checkbox :model-value="tunnelMode === 'split'" class="mt-0.5" />
            <span class="text-sm">
              <span class="text-foreground">Access this LAN only</span>
              <span class="block text-xs text-muted-foreground">Reach devices at home; everything else stays on the device's normal connection. Recommended.</span>
            </span>
          </button>
          <button type="button" class="flex items-start gap-2 text-left w-full" @click="tunnelMode = 'full'">
            <Checkbox :model-value="tunnelMode === 'full'" class="mt-0.5" />
            <span class="text-sm">
              <span class="text-foreground">Full tunnel with kill switch</span>
              <span class="block text-xs text-muted-foreground">Route all of the device's traffic through this box, and block traffic if the tunnel drops.</span>
            </span>
          </button>
        </div>
      </div>

      <!-- one-time reveal -->
      <div v-else class="space-y-4">
        <Alert variant="warning">
          <AlertDescription>This is the only time you'll see this key. Scan the code or download the file now.</AlertDescription>
        </Alert>
        <div class="flex justify-center">
          <img :src="secretQrSrc" alt="WireGuard peer QR code" class="w-48 h-48 rounded bg-white p-2" />
        </div>
        <div class="flex justify-center">
          <Button variant="secondary" size="sm" @click="downloadSecretConf">Download .conf</Button>
        </div>
        <div>
          <button type="button" class="text-xs text-muted-foreground hover:text-foreground flex items-center gap-2" @click="showRawConf = !showRawConf">
            <svg
              viewBox="0 0 24 24"
              class="w-3.5 h-3.5 transition-transform duration-150"
              :class="{ 'rotate-90': showRawConf }"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              aria-hidden="true"
            >
              <path d="M9 6l6 6-6 6" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            Show raw config
          </button>
          <pre v-if="showRawConf" class="mt-2 text-xs font-mono bg-muted rounded p-3 overflow-auto whitespace-pre-wrap">{{ secret.confText }}</pre>
        </div>
      </div>

      <template #footer>
        <template v-if="!secret">
          <Button variant="secondary" @click="closeAdd">Cancel</Button>
          <Button :disabled="adding" @click="submitAdd">{{ adding ? 'Adding…' : 'Add peer' }}</Button>
        </template>
        <Button v-else @click="closeAdd">Done</Button>
      </template>
    </Dialog>
  </section>
</template>
