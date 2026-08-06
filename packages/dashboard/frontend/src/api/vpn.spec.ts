import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import axios, { AxiosError, type AxiosAdapter, type AxiosResponse } from 'axios';
import { http, _resetToastDedupe } from './client';
import { VpnApi, peerOnline, type VpnPeer, type VpnPeerSecret, type VpnStatus } from './vpn';
import { dismissAll, useToastQueue } from '@/composables/useToast';

/**
 * VpnApi — endpoint plumbing + the client-side peerOnline() derivation.
 * Same shape of coverage as mdns.spec.ts: happy path per method, plus
 * the toast-on-5xx contract (VpnApi opts in to the default global
 * toast everywhere; none of these calls render their own error banner
 * mid-flight the way the mdns reconcile button does).
 */

const STATUS_SAMPLE: VpnStatus = {
  runState: 'running',
  interface: 'wg0',
  listenPort: 51820,
  endpoint: 'aurora.example.com:51820',
  peersTotal: 2,
  peersOnline: 1,
  reachable: true,
  lastCheckedAt: '2026-08-06T08:00:00Z',
  generatedAt: '2026-08-06T08:00:05Z',
};

const PEER_SAMPLE: VpnPeer = {
  id: 'peer-1',
  name: "Bruce's phone",
  publicKey: 'abc123=',
  allowedIps: '192.168.1.0/24, 10.66.66.2/32',
  killSwitch: false,
  enabled: true,
  lastHandshakeAt: '2026-08-06T07:59:00Z',
  rxBytes: 1024,
  txBytes: 2048,
  createdAt: '2026-08-01T10:00:00Z',
};

const SECRET_SAMPLE: VpnPeerSecret = {
  peer: PEER_SAMPLE,
  privateKey: 'zzz999=',
  qrPngBase64: 'iVBORw0KGgo=',
  confText: '[Interface]\nPrivateKey = zzz999=\n',
};

function installAdapter(mode: 'ok', body?: unknown): void;
function installAdapter(mode: { status: number }): void;
function installAdapter(mode: 'ok' | { status: number }, body?: unknown): void {
  const adapter: AxiosAdapter = (config) => {
    if (mode === 'ok') {
      return Promise.resolve({
        data: body,
        status: config.method === 'post' && config.url?.includes('/peers') && !config.url.includes('toggle') ? 201 : 200,
        statusText: 'OK',
        headers: {},
        config,
      } as AxiosResponse);
    }
    const err = new AxiosError('boom', 'ERR_BAD_RESPONSE', config);
    err.response = {
      status: mode.status, statusText: 'err', data: null, headers: {}, config,
    } as AxiosResponse;
    return Promise.reject(err);
  };
  http.defaults.adapter = adapter;
}

beforeEach(() => {
  dismissAll();
  _resetToastDedupe();
});
afterEach(() => {
  dismissAll();
  _resetToastDedupe();
  delete (http.defaults as { adapter?: AxiosAdapter }).adapter;
});

describe('VpnApi', () => {
  it('status() returns the snapshot as-is', async () => {
    installAdapter('ok', STATUS_SAMPLE);
    const s = await VpnApi.status();
    expect(s.runState).toBe('running');
    expect(s.peersOnline).toBe(1);
    expect(s.reachable).toBe(true);
  });

  it('peers() returns the peer list', async () => {
    installAdapter('ok', [PEER_SAMPLE]);
    const peers = await VpnApi.peers();
    expect(peers).toHaveLength(1);
    expect(peers[0].name).toBe("Bruce's phone");
  });

  it('addPeer() posts the name + allowedIpsMode and returns a one-time secret', async () => {
    installAdapter('ok', SECRET_SAMPLE);
    const secret = await VpnApi.addPeer("Bruce's phone", 'split');
    expect(secret.peer.id).toBe('peer-1');
    expect(secret.privateKey).toBe('zzz999=');
    expect(secret.qrPngBase64.length).toBeGreaterThan(0);
  });

  it('togglePeer() returns the flipped peer', async () => {
    installAdapter('ok', { ...PEER_SAMPLE, enabled: false });
    const peer = await VpnApi.togglePeer('peer-1');
    expect(peer.enabled).toBe(false);
  });

  it('removePeer() resolves on 204', async () => {
    installAdapter('ok', undefined);
    await expect(VpnApi.removePeer('peer-1')).resolves.toBeUndefined();
  });

  it('peerConfigUrl() / peerQrCodeUrl() build stable, encoded paths', () => {
    expect(VpnApi.peerConfigUrl('peer 1')).toBe('/api/vpn/peers/peer%201/config');
    expect(VpnApi.peerQrCodeUrl('peer-1')).toBe('/api/vpn/peers/peer-1/qrcode');
  });

  it('status() 500 raises the global destructive toast (no opt-out)', async () => {
    installAdapter({ status: 500 });
    await expect(VpnApi.status()).rejects.toBeInstanceOf(AxiosError);
    expect(useToastQueue().queue).toHaveLength(1);
    expect(useToastQueue().queue[0].variant).toBe('destructive');
  });
});

describe('peerOnline', () => {
  const now = new Date('2026-08-06T08:00:00Z').getTime();

  it('is true within the 3-minute handshake window', () => {
    const peer = { ...PEER_SAMPLE, lastHandshakeAt: '2026-08-06T07:58:00Z' };
    expect(peerOnline(peer, now)).toBe(true);
  });

  it('is false once the handshake is older than 3 minutes', () => {
    const peer = { ...PEER_SAMPLE, lastHandshakeAt: '2026-08-06T07:56:00Z' };
    expect(peerOnline(peer, now)).toBe(false);
  });

  it('is false with no handshake yet', () => {
    const peer = { ...PEER_SAMPLE, lastHandshakeAt: null };
    expect(peerOnline(peer, now)).toBe(false);
  });

  it('is false when the peer is disabled, even with a fresh handshake', () => {
    const peer = { ...PEER_SAMPLE, enabled: false, lastHandshakeAt: '2026-08-06T07:59:59Z' };
    expect(peerOnline(peer, now)).toBe(false);
  });
});
