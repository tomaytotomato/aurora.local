// Aurora's own inbound VPN server: remote access into the LAN from
// outside, for a person's own phone or laptop. WireGuard is the default
// and the only protocol with the full flow (peers, QR onboarding, kill
// switch); OpenVPN is a secondary, de-emphasised option for a device
// that can't run a WireGuard client.
//
// NOT the same thing as packages/privacy's Gluetun sidecar, which is an
// OUTBOUND client tunnel that anonymises traffic leaving the media
// stack. Same three letters, opposite direction — see VpnView.vue's
// header callout, which says so explicitly.
//
// See docs/VPN_PAGE_DESIGN.md for the full design rationale.

import { http } from './client';

export type VpnRunState = 'running' | 'stopped' | 'degraded' | 'not-configured';
export type AllowedIpsMode = 'split' | 'full';

export interface VpnStatus {
  runState: VpnRunState;
  /** e.g. "wg0"; null while not-configured. */
  interface: string | null;
  listenPort: number | null;
  /** host:port once known. */
  endpoint: string | null;
  peersTotal: number;
  /** Handshake within the last 3 minutes. */
  peersOnline: number;
  /** null = not checked yet; the backend probes an external service. */
  reachable: boolean | null;
  lastCheckedAt: string | null;
  generatedAt: string;
}

export interface VpnConfig {
  /** DDNS name or public IP; may be '' before the person fills it in. */
  endpointHost: string;
  listenPort: number;
  /** Pushed to peers. Defaults to the Privacy package's AdGuard IP if enabled, else a public resolver. */
  dns: string;
  /** Tunnel subnet, e.g. "10.66.66.1/24". */
  serverAddress: string;
  mtu: number;
  /** null until POST /vpn/config/init has run once. */
  serverPublicKey: string | null;
}

export interface VpnPeer {
  id: string;
  name: string;
  publicKey: string;
  /** What's actually pushed to this peer's client routing table. */
  allowedIps: string;
  /** true when this peer was created as full-tunnel (routes everything + firewall drop on tunnel loss). */
  killSwitch: boolean;
  enabled: boolean;
  lastHandshakeAt: string | null;
  rxBytes: number;
  txBytes: number;
  createdAt: string;
}

/**
 * Returned exactly once, from `addPeer`. The private key is never
 * retrievable again afterwards — there is no `reveal=1` for a WireGuard
 * key the way there is for a package env secret. Losing it means
 * removing the peer and adding a new one.
 */
export interface VpnPeerSecret {
  peer: VpnPeer;
  privateKey: string;
  qrPngBase64: string;
  confText: string;
}

export interface OpenVpnConfig {
  enabled: boolean;
  port: number;
  protocol: 'udp' | 'tcp';
}

export interface OpenVpnClient {
  id: string;
  name: string;
  createdAt: string;
  lastConnectedAt: string | null;
}

export interface OpenVpnClientSecret {
  client: OpenVpnClient;
  confText: string;
}

export const VpnApi = {
  async status(): Promise<VpnStatus> {
    const { data } = await http.get<VpnStatus>('/vpn/status');
    return data;
  },

  async config(): Promise<VpnConfig> {
    const { data } = await http.get<VpnConfig>('/vpn/config');
    return data;
  },

  /** First-run: generate a server keypair + sensible defaults. 409 if already configured. */
  async initConfig(): Promise<VpnConfig> {
    const { data } = await http.post<VpnConfig>('/vpn/config/init');
    return data;
  },

  /**
   * Undo initConfig: discard the server keypair and every peer, returning
   * the box to not-configured. Destructive — a peer's issued .conf
   * authenticates against the key being discarded.
   */
  async removeConfig(): Promise<void> {
    await http.delete('/vpn/config');
  },

  async saveConfig(patch: Partial<VpnConfig>): Promise<VpnConfig> {
    const { data } = await http.put<VpnConfig>('/vpn/config', patch);
    return data;
  },

  /** Destructive: every existing peer's .conf becomes wrong until re-downloaded. */
  async rotateServerKey(): Promise<VpnConfig> {
    const { data } = await http.post<VpnConfig>('/vpn/server/rotate-key');
    return data;
  },

  async peers(): Promise<VpnPeer[]> {
    const { data } = await http.get<VpnPeer[]>('/vpn/peers');
    return data;
  },

  async addPeer(name: string, allowedIpsMode: AllowedIpsMode): Promise<VpnPeerSecret> {
    const { data } = await http.post<VpnPeerSecret>('/vpn/peers', { name, allowedIpsMode });
    return data;
  },

  async removePeer(id: string): Promise<void> {
    await http.delete(`/vpn/peers/${encodeURIComponent(id)}`);
  },

  /** Suspend/resume a peer without deleting it. */
  async togglePeer(id: string): Promise<VpnPeer> {
    const { data } = await http.post<VpnPeer>(`/vpn/peers/${encodeURIComponent(id)}/toggle`);
    return data;
  },

  /** Plain URL for an `<a download>` — same pattern as OnboardingApi.caddyRootCaUrl(). */
  peerConfigUrl(id: string): string {
    return `/api/vpn/peers/${encodeURIComponent(id)}/config`;
  },

  /** Plain URL for an `<img>` src. */
  peerQrCodeUrl(id: string): string {
    return `/api/vpn/peers/${encodeURIComponent(id)}/qrcode`;
  },

  async openVpnConfig(): Promise<OpenVpnConfig> {
    const { data } = await http.get<OpenVpnConfig>('/vpn/openvpn/config');
    return data;
  },

  async saveOpenVpnConfig(patch: Partial<OpenVpnConfig>): Promise<OpenVpnConfig> {
    const { data } = await http.put<OpenVpnConfig>('/vpn/openvpn/config', patch);
    return data;
  },

  async openVpnClients(): Promise<OpenVpnClient[]> {
    const { data } = await http.get<OpenVpnClient[]>('/vpn/openvpn/clients');
    return data;
  },

  async addOpenVpnClient(name: string): Promise<OpenVpnClientSecret> {
    const { data } = await http.post<OpenVpnClientSecret>('/vpn/openvpn/clients', { name });
    return data;
  },

  async removeOpenVpnClient(id: string): Promise<void> {
    await http.delete(`/vpn/openvpn/clients/${encodeURIComponent(id)}`);
  },
};

/** Client-side derivation: a peer counts as online if it's shaken hands recently. */
export function peerOnline(peer: VpnPeer, nowMs = Date.now()): boolean {
  if (!peer.enabled || !peer.lastHandshakeAt) return false;
  const handshake = new Date(peer.lastHandshakeAt).getTime();
  if (Number.isNaN(handshake)) return false;
  return nowMs - handshake <= 3 * 60_000;
}
