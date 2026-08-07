// MSW request handlers covering the full /api surface described in
// packages/dashboard/openapi.yaml. Grouped by domain. Stateful bits read
// and write ../state so the UI behaves like a real backend within a
// session.

import { http, HttpResponse } from 'msw';

import type { OnboardingPatch } from '@/api/onboarding';
import type { PackageSummary } from '@/api/packages';
import type { ServiceStatus } from '@/api/services';
import type { OpenVpnClient, VpnPeer, VpnRunState, VpnStatus } from '@/api/vpn';
import { peerOnline } from '@/api/vpn';
import type { User, UserRole } from '@/api/users';

import { state } from './state';
import { sseResponse } from './sse';
import { PLACEHOLDER_QR_PNG_BASE64, peerConfText } from './fixtures/vpn';
import { detailFor, envFor, packageSeeds } from './fixtures/packages';
import { metricSamples, onboardingEnv, stateFile, systemInfo } from './fixtures/system';
import { servicesStatus } from './fixtures/services';
import {
  auditEvents,
  containerLogs,
  containers,
  dismissals,
  mdnsAliases,
  metricBuckets,
  metricKeys,
  recentEvents,
  securityFindings,
} from './fixtures/observability';

const noContent = () => new HttpResponse(null, { status: 204 });
const nowIso = () => new Date().toISOString();

/** Apply the live enabled/running mock state onto the catalogue shape. */
function liveSummary(base: PackageSummary): PackageSummary {
  return {
    ...base,
    enabled: state.enabled.has(base.name),
    running: state.running.has(base.name),
  };
}

// ─────────────────────────────── auth ───────────────────────────────
const auth = [
  http.get('/api/auth/session', () => HttpResponse.json(state.session)),
  http.post('/api/auth/login', async ({ request }) => {
    const { username } = (await request.json()) as { username: string; password: string };
    state.session = { ...state.session, authenticated: true, username: username || 'admin' };
    return HttpResponse.json(state.session);
  }),
  http.post('/api/auth/logout', () => {
    state.session = { ...state.session, authenticated: false, username: null };
    return noContent();
  }),
];

// ──────────────────────────── onboarding ────────────────────────────
// Snake_case wire shape of GET /onboarding/plan (api/onboarding.ts keeps
// this type private, so the mock mirrors it locally).
interface PlanWire {
  packages_to_enable: string[];
  packages_to_disable: string[];
  vhosts: string[];
  ports: number[];
  warnings: string[];
}

function planFor(enabled: string[]): PlanWire {
  const seeds = packageSeeds.filter((s) => enabled.includes(s.summary.name));
  const vhosts = seeds.flatMap((s) => s.detail.vhosts ?? []);
  const ports = seeds.flatMap((s) => (s.summary.ports ?? []).map((p) => Number((p as { host?: number }).host)).filter(Boolean));
  const warnings: string[] = [];
  if (enabled.includes('media') && !enabled.includes('privacy')) {
    warnings.push('Media runs behind the privacy VPN; enable privacy too.');
  }
  if (enabled.includes('ai')) {
    warnings.push('AI (Ollama) is memory-hungry; 8 GB+ recommended.');
  }
  return {
    packages_to_enable: enabled,
    packages_to_disable: [],
    vhosts,
    ports,
    warnings,
  };
}

const onboarding = [
  http.get('/api/onboarding', () => HttpResponse.json(state.onboarding)),
  http.patch('/api/onboarding', async ({ request }) => {
    const patch = (await request.json()) as OnboardingPatch;
    if (patch.domain !== undefined) state.onboarding.domain = patch.domain;
    if (patch.dns_mode !== undefined) state.onboarding.dns_mode = patch.dns_mode;
    if (patch.step !== undefined) state.onboarding.step = patch.step;
    if (patch.enabled_packages !== undefined) {
      state.onboarding.enabled_packages = patch.enabled_packages;
      state.enabled = new Set(patch.enabled_packages);
    }
    return HttpResponse.json(state.onboarding);
  }),
  http.get('/api/onboarding/status', () =>
    HttpResponse.json({
      complete: state.onboarding.complete,
      bootstrap_mode: state.onboarding.bootstrap_mode,
      step: state.onboarding.step,
    }),
  ),
  http.get('/api/onboarding/env', () => HttpResponse.json(onboardingEnv)),
  http.post('/api/onboarding/admin', async ({ request }) => {
    const { username } = (await request.json()) as { username: string; password: string };
    state.onboarding.admin_username = username;
    return noContent();
  }),
  http.get('/api/onboarding/plan', ({ request }) => {
    const enabledParam = new URL(request.url).searchParams.get('enabled');
    const enabled = enabledParam ? enabledParam.split(',').filter(Boolean) : [...state.enabled];
    return HttpResponse.json(planFor(enabled));
  }),
  http.post('/api/onboarding/install', () =>
    HttpResponse.json({
      applied: [...state.enabled],
      packages_to_start: [...state.enabled],
      packages_to_stop: [],
      host_command: 'sudo ./scripts/up.sh',
    }),
  ),
  http.post('/api/onboarding/launch', () =>
    HttpResponse.json(
      { job_id: 'mock-launch', packages: [...state.enabled], started_at: nowIso() },
      { status: 202 },
    ),
  ),
  http.get('/api/onboarding/launch/:id', ({ params }) =>
    HttpResponse.json({
      id: String(params.id),
      state: 'running',
      packages: [...state.enabled],
      started_at: nowIso(),
      finished_at: null,
      exit_code: null,
      failure_reason: null,
      tail: ['[mock] bringing up services…'],
    }),
  ),
  // SSE: stream a short launch log, then a terminal success `done` event.
  http.get('/api/onboarding/launch/:id/stream', () =>
    sseResponse((emit) => {
      const log = [
        'Creating network aurora_default',
        'Pulling core (caddy:2.8)…',
        'Starting aurora-caddy … done',
        'Starting aurora-privacy-adguard … done',
        'Starting aurora-monitoring-grafana … done',
        'All services healthy',
      ];
      let i = 0;
      const timer = setInterval(() => {
        if (i < log.length) {
          emit({ event: 'log', data: log[i++] });
          return;
        }
        emit({ event: 'done', data: JSON.stringify({ state: 'success', reason: null }) });
        clearInterval(timer);
      }, 700);
      return () => clearInterval(timer);
    }),
  ),
  http.post('/api/onboarding/complete', () => {
    state.onboarding.complete = true;
    state.onboarding.bootstrap_mode = false;
    state.onboarding.step = 'done';
    return noContent();
  }),
];

// ───────────────────────────── packages ─────────────────────────────
const packages = [
  http.get('/api/packages', () =>
    HttpResponse.json(packageSeeds.map((s) => liveSummary(s.summary))),
  ),
  http.get('/api/packages/:name', ({ params }) => {
    const name = String(params.name);
    const detail = detailFor(name);
    if (!detail) return new HttpResponse(null, { status: 404 });
    // envVars carries the *specs* (key/secret/required/example/comment),
    // not values — GET .../env is the values endpoint. The real API
    // documents both on PackageDetail (see openapi.yaml); this handler
    // was missing the specs, so the Config tab had nothing to render.
    return HttpResponse.json({ ...detail, ...liveSummary(detail), envVars: envFor(name) });
  }),
  http.get('/api/packages/:name/env', ({ params, request }) => {
    const reveal = new URL(request.url).searchParams.has('reveal');
    const map: Record<string, string> = {};
    for (const spec of envFor(String(params.name))) {
      const real = spec.value ?? spec.example ?? '';
      map[spec.key] = spec.secret && !reveal ? '••••••••' : real;
    }
    return HttpResponse.json(map);
  }),
  http.put('/api/packages/:name/env', () => noContent()),
  http.post('/api/packages/:name/enable', ({ params }) => {
    const name = String(params.name);
    state.enabled.add(name);
    state.running.add(name);
    return HttpResponse.json({ jobId: `enable-${name}` }, { status: 202 });
  }),
  http.post('/api/packages/:name/disable', ({ params }) => {
    const name = String(params.name);
    state.enabled.delete(name);
    state.running.delete(name);
    return HttpResponse.json({ jobId: `disable-${name}` }, { status: 202 });
  }),
  http.post('/api/packages/:name/restart', () => noContent()),
  http.post('/api/packages/:name/upgrade', ({ params }) =>
    HttpResponse.json({ jobId: `upgrade-${String(params.name)}` }, { status: 202 }),
  ),
];

// ───────────────────────────── services ─────────────────────────────
/** Nudge the "starting" services toward running so the stream animates. */
function progress(services: ServiceStatus[], tick: number): ServiceStatus[] {
  if (tick < 2) return services;
  const advance = (s: ServiceStatus): ServiceStatus => ({
    ...s,
    state: s.state === 'starting' || s.state === 'not-started' ? 'running' : s.state,
    detail: s.state === 'starting' ? null : s.detail,
    children: s.children?.map(advance),
  });
  return services.map(advance);
}

const services = [
  http.get('/api/services/status', () => HttpResponse.json(servicesStatus(nowIso()))),
  http.get('/api/services/status/stream', () =>
    sseResponse((emit) => {
      let tick = 0;
      const send = () => {
        const base = servicesStatus(nowIso());
        emit({ event: 'service-status', data: JSON.stringify({ ...base, services: progress(base.services, tick) }) });
        tick += 1;
      };
      send();
      const timer = setInterval(send, 3_000);
      return () => clearInterval(timer);
    }),
  ),
  http.post('/api/services/:package/start', ({ params }) => {
    const name = String(params.package);
    state.running.add(name);
    return HttpResponse.json({ job_id: `start-${name}`, packages: [name], started_at: nowIso() }, { status: 202 });
  }),
];

// ──────────────────────────── containers ────────────────────────────
const containersHandlers = [
  http.get('/api/containers', ({ request }) => {
    const pkg = new URL(request.url).searchParams.get('package');
    const rows = pkg ? containers.filter((c) => c.labels['aurora.package'] === pkg) : containers;
    return HttpResponse.json(rows);
  }),
  http.get('/api/containers/events', () => HttpResponse.json(recentEvents)),
  http.get('/api/containers/events/stream', () =>
    sseResponse((emit) => {
      for (const ev of recentEvents) emit({ event: 'container-event', data: JSON.stringify(ev) });
      const actions = ['start', 'health:healthy', 'restart', 'health:unhealthy'];
      let i = 0;
      const timer = setInterval(() => {
        emit({
          event: 'container-event',
          data: JSON.stringify({ ts: Date.now(), container: 'aurora-media-sonarr', action: actions[i % actions.length] }),
        });
        i += 1;
      }, 4_000);
      return () => clearInterval(timer);
    }),
  ),
  http.get('/api/containers/:id/logs', ({ params, request }) => {
    const tail = Number(new URL(request.url).searchParams.get('tail') ?? 200);
    return HttpResponse.json(containerLogs(String(params.id), tail));
  }),
];

// ───────────────────────────── metrics ──────────────────────────────
const metrics = [
  http.get('/api/metrics/keys', ({ request }) => {
    const prefix = new URL(request.url).searchParams.get('prefix') ?? undefined;
    return HttpResponse.json(metricKeys(prefix));
  }),
  http.get('/api/metrics/last24h', ({ request }) => {
    const bucket = Number(new URL(request.url).searchParams.get('bucketMinutes') ?? 5);
    return HttpResponse.json(metricBuckets(Date.now(), bucket));
  }),
];

// ───────────────────────────── security ─────────────────────────────
const dismissedIds = new Set(dismissals.map((d) => d.finding_id));
const security = [
  http.get('/api/security/findings', ({ request }) => {
    const includeDismissed = new URL(request.url).searchParams.has('includeDismissed');
    const rows = includeDismissed
      ? securityFindings
      : securityFindings.filter((f) => !dismissedIds.has(f.id));
    return HttpResponse.json(rows);
  }),
  http.post('/api/security/findings/:id/dismiss', ({ params }) => {
    dismissedIds.add(String(params.id));
    return noContent();
  }),
  http.delete('/api/security/findings/:id/dismiss', ({ params }) => {
    dismissedIds.delete(String(params.id));
    return noContent();
  }),
  http.get('/api/security/dismissals', () => HttpResponse.json(dismissals)),
];

// ────────────────────────────── audit ───────────────────────────────
const audit = [
  http.get('/api/audit/events', () => HttpResponse.json(auditEvents)),
];

// ────────────────────────────── system ──────────────────────────────
const system = [
  http.get('/api/system', () => HttpResponse.json(systemInfo)),
  http.get('/api/system/metrics', ({ request }) => {
    const window = (new URL(request.url).searchParams.get('window') ?? '24h') as '1h' | '24h' | '7d';
    return HttpResponse.json(metricSamples(Date.now(), window));
  }),
  http.get('/api/system/state', () => HttpResponse.json(stateFile)),
  http.get('/api/system/caddy-root.crt', () =>
    new HttpResponse('-----BEGIN CERTIFICATE-----\nMOCKCERT\n-----END CERTIFICATE-----\n', {
      headers: { 'Content-Type': 'application/x-x509-ca-cert' },
    }),
  ),
];

// ─────────────────────────────── mdns ───────────────────────────────
const mdns = [
  http.get('/api/mdns/aliases', () => HttpResponse.json(mdnsAliases)),
  http.post('/api/mdns/reconcile', () => HttpResponse.json(mdnsAliases)),
];

// ─────────────────────────────── vpn ────────────────────────────────
// Aurora's inbound WireGuard server. See docs/VPN_PAGE_DESIGN.md.
function vpnStatus(reachable: boolean | null): VpnStatus {
  const v = state.vpn;
  const runState: VpnRunState = v.config === null ? 'not-configured' : 'running';
  return {
    runState,
    interface: v.config ? 'wg0' : null,
    listenPort: v.config?.listenPort ?? null,
    endpoint: v.config ? `${v.config.endpointHost}:${v.config.listenPort}` : null,
    peersTotal: v.peers.length,
    peersOnline: v.peers.filter((p) => peerOnline(p)).length,
    reachable,
    lastCheckedAt: nowIso(),
    generatedAt: nowIso(),
  };
}

function pngBytes(b64: string): Uint8Array {
  const bin = atob(b64);
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

const vpn = [
  http.get('/api/vpn/status', () => HttpResponse.json(vpnStatus(true))),
  http.get('/api/vpn/status/stream', () =>
    sseResponse((emit) => {
      // Nudge reachability so the Overview tab visibly ticks in dev:
      // checking → reachable → reachable, looping.
      const cycle: (boolean | null)[] = [null, true, true];
      let i = 0;
      const send = () => emit({ event: 'vpn-status', data: JSON.stringify(vpnStatus(cycle[i++ % cycle.length])) });
      send();
      const timer = setInterval(send, 4_000);
      return () => clearInterval(timer);
    }),
  ),
  http.get('/api/vpn/config', () => {
    if (state.vpn.config === null) return new HttpResponse(null, { status: 404 });
    return HttpResponse.json(state.vpn.config);
  }),
  http.post('/api/vpn/config/init', () => {
    if (state.vpn.config === null) {
      state.vpn.config = {
        endpointHost: '',
        listenPort: 51820,
        dns: state.enabled.has('privacy') ? '192.168.1.10' : '1.1.1.1',
        serverAddress: '10.66.66.1/24',
        mtu: 1420,
        serverPublicKey: 'sVrPubK3y' + Math.random().toString(36).slice(2, 10) + '=',
      };
    }
    return HttpResponse.json(state.vpn.config);
  }),
  http.put('/api/vpn/config', async ({ request }) => {
    const patch = (await request.json()) as Partial<NonNullable<typeof state.vpn.config>>;
    state.vpn.config = { ...(state.vpn.config ?? {}), ...patch } as typeof state.vpn.config;
    return HttpResponse.json(state.vpn.config);
  }),
  http.post('/api/vpn/server/rotate-key', () => {
    if (state.vpn.config) {
      state.vpn.config = { ...state.vpn.config, serverPublicKey: 'rotated' + Math.random().toString(36).slice(2, 10) + '=' };
    }
    return HttpResponse.json(state.vpn.config);
  }),
  http.get('/api/vpn/peers', () => HttpResponse.json(state.vpn.peers)),
  http.post('/api/vpn/peers', async ({ request }) => {
    const { name, allowedIpsMode } = (await request.json()) as { name: string; allowedIpsMode: 'split' | 'full' };
    const idx = state.vpn.peers.length + 2;
    const full = allowedIpsMode === 'full';
    const peer: VpnPeer = {
      id: 'peer-' + Math.random().toString(36).slice(2, 8),
      name: name || `Device ${idx}`,
      publicKey: 'pub' + Math.random().toString(36).slice(2, 12) + '=',
      allowedIps: full ? '0.0.0.0/0' : `192.168.1.0/24, 10.66.66.${idx}/32`,
      killSwitch: full,
      enabled: true,
      lastHandshakeAt: null,
      rxBytes: 0,
      txBytes: 0,
      createdAt: nowIso(),
    };
    state.vpn.peers = [...state.vpn.peers, peer];
    return HttpResponse.json(
      {
        peer,
        privateKey: 'PRIV' + Math.random().toString(36).slice(2, 20) + '=',
        qrPngBase64: PLACEHOLDER_QR_PNG_BASE64,
        confText: peerConfText(peer.name, state.vpn.config ?? { endpointHost: 'aurora.local', listenPort: 51820, dns: '1.1.1.1', serverAddress: '10.66.66.1/24', mtu: 1420, serverPublicKey: null }),
      },
      { status: 201 },
    );
  }),
  http.delete('/api/vpn/peers/:id', ({ params }) => {
    state.vpn.peers = state.vpn.peers.filter((p) => p.id !== String(params.id));
    return noContent();
  }),
  http.post('/api/vpn/peers/:id/toggle', ({ params }) => {
    const peer = state.vpn.peers.find((p) => p.id === String(params.id));
    if (!peer) return new HttpResponse(null, { status: 404 });
    peer.enabled = !peer.enabled;
    return HttpResponse.json(peer);
  }),
  http.get('/api/vpn/peers/:id/config', ({ params }) => {
    const peer = state.vpn.peers.find((p) => p.id === String(params.id));
    const body = peerConfText(peer?.name ?? 'peer', state.vpn.config ?? { endpointHost: 'aurora.local', listenPort: 51820, dns: '1.1.1.1', serverAddress: '10.66.66.1/24', mtu: 1420, serverPublicKey: null });
    return new HttpResponse(body, {
      headers: { 'Content-Type': 'text/plain', 'Content-Disposition': `attachment; filename="${peer?.id ?? 'peer'}.conf"` },
    });
  }),
  http.get('/api/vpn/peers/:id/qrcode', () =>
    new HttpResponse(pngBytes(PLACEHOLDER_QR_PNG_BASE64), { headers: { 'Content-Type': 'image/png' } }),
  ),
  http.get('/api/vpn/openvpn/config', () => HttpResponse.json(state.vpn.openVpn)),
  http.put('/api/vpn/openvpn/config', async ({ request }) => {
    const patch = (await request.json()) as Partial<typeof state.vpn.openVpn>;
    state.vpn.openVpn = { ...state.vpn.openVpn, ...patch };
    return HttpResponse.json(state.vpn.openVpn);
  }),
  http.get('/api/vpn/openvpn/clients', () => HttpResponse.json(state.vpn.openVpnClients)),
  http.post('/api/vpn/openvpn/clients', async ({ request }) => {
    const { name } = (await request.json()) as { name: string };
    const client: OpenVpnClient = {
      id: 'ovpn-' + Math.random().toString(36).slice(2, 8),
      name: name || 'client',
      createdAt: nowIso(),
      lastConnectedAt: null,
    };
    state.vpn.openVpnClients = [...state.vpn.openVpnClients, client];
    return HttpResponse.json({ client, confText: `client\ndev tun\nproto ${state.vpn.openVpn.protocol}\nremote aurora.local ${state.vpn.openVpn.port}\n` }, { status: 201 });
  }),
  http.delete('/api/vpn/openvpn/clients/:id', ({ params }) => {
    state.vpn.openVpnClients = state.vpn.openVpnClients.filter((c) => c.id !== String(params.id));
    return noContent();
  }),
];

// ─────────────────────────────── users ─────────────────────────────
const users = [
  http.get('/api/users', () => HttpResponse.json(state.users)),
  http.post('/api/users', async ({ request }) => {
    const body = (await request.json()) as { username: string; role: UserRole; password: string };
    if (state.users.some((u) => u.username === body.username)) {
      return HttpResponse.json({ message: 'That username is already taken.' }, { status: 409 });
    }
    const user: User = {
      id: 'user-' + Math.random().toString(36).slice(2, 8),
      username: body.username,
      role: body.role,
      createdAt: nowIso(),
      lastLoginAt: null,
      passkeyEnrolled: false,
    };
    state.users = [...state.users, user];
    return HttpResponse.json(user, { status: 201 });
  }),
  http.patch('/api/users/:id', async ({ params, request }) => {
    const patch = (await request.json()) as { role?: UserRole };
    const user = state.users.find((u) => u.id === String(params.id));
    if (!user) return new HttpResponse(null, { status: 404 });
    if (patch.role) user.role = patch.role;
    return HttpResponse.json(user);
  }),
  http.delete('/api/users/:id', ({ params }) => {
    const id = String(params.id);
    if (id === state.currentUserId) {
      return HttpResponse.json({ message: "You can't remove your own account." }, { status: 409 });
    }
    state.users = state.users.filter((u) => u.id !== id);
    return noContent();
  }),
];

export const handlers = [
  ...auth,
  ...users,
  ...onboarding,
  ...packages,
  ...services,
  ...containersHandlers,
  ...metrics,
  ...security,
  ...audit,
  ...system,
  ...mdns,
  ...vpn,
];
