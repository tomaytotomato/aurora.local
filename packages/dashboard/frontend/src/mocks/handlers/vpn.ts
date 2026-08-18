// Aurora's inbound WireGuard server. See docs/VPN_PAGE_DESIGN.md.

import { http, HttpResponse } from 'msw';

import type { OpenVpnClient, VpnPeer, VpnRunState, VpnStatus } from '@/api/vpn';
import { peerOnline } from '@/api/vpn';

import { state } from '../state';
import { sseResponse } from '../sse';
import { PLACEHOLDER_QR_PNG_BASE64, peerConfText } from '../fixtures/vpn';
import { noContent, nowIso } from './shared';

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

const FALLBACK_CONFIG = {
  endpointHost: 'aurora.local',
  listenPort: 51820,
  dns: '1.1.1.1',
  serverAddress: '10.66.66.1/24',
  mtu: 1420,
  serverPublicKey: null,
};

export const vpnHandlers = [
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
  // Undo of config/init. Peers go too, mirroring the backend: their
  // issued .conf authenticates against the key being discarded.
  http.delete('/api/vpn/config', () => {
    if (state.vpn.config === null) return new HttpResponse(null, { status: 404 });
    state.vpn.config = null;
    state.vpn.peers = [];
    return noContent();
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
        confText: peerConfText(peer.name, state.vpn.config ?? FALLBACK_CONFIG),
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
    const body = peerConfText(peer?.name ?? 'peer', state.vpn.config ?? FALLBACK_CONFIG);
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
