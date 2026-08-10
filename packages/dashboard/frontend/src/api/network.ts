// What an app's traffic is allowed to do, in both directions.
//
// Outbound: which apps egress through the VPN gateway rather than the
// normal WAN route. The mechanism is container network-namespace
// sharing (`network_mode: service:gluetun`), documented in
// docs/SPLIT_TUNNEL.md. It was chosen over host-level policy routing
// precisely because it is safe to flip from a UI without rewriting the
// host's routing table.
//
// Inbound: rate limiting, geo-blocking and bot detection in Caddy in
// front of anything exposed past the LAN.
//
// The two live in one module because they are one question — what is
// this app allowed to talk to, and who is allowed to talk to it — and
// they share a tab in the UI.

import { http } from './client';
import type { JobRef } from './packages';

export type EgressMode = 'direct' | 'vpn';

export interface PackageNetwork {
  package: string;
  mode: EgressMode;
  /** Gateway container whose namespace this app would share. */
  gateway: string | null;
  /** True when this app's egress cannot be changed from here. */
  locked: boolean;
  lockedReason: string | null;
  /** Containers that would restart when the mode changes. */
  containers: string[];
  /** Host ports this app publishes; they move onto the gateway when tunnelled. */
  publishedPorts: number[];
  /** What the outside world currently sees as this app's source address. */
  egressIp: string | null;
  egressCountry: string | null;
  /** True when the gateway is up. A tunnelled app has no network at all without it. */
  gatewayHealthy: boolean;
}

export interface RateLimit {
  enabled: boolean;
  requestsPerMinute: number;
}

export interface GeoBlock {
  enabled: boolean;
  /** ISO-3166 alpha-2, allow-list. Empty with enabled=true blocks everything. */
  allowCountries: string[];
}

export interface VhostProtection {
  vhost: string;
  package: string;
  /**
   * True when this name resolves from outside the LAN. Aurora defaults
   * protection on for these and leaves LAN-only vhosts alone, because
   * rate-limiting your own laptop is pure downside.
   */
  publiclyResolvable: boolean;
  /** Behind Authelia — already a serious barrier, so protection matters less. */
  authelia: boolean;
  rateLimit: RateLimit;
  geoBlock: GeoBlock;
  botDetection: boolean;
  blocked24h: number;
  lastBlockedAt: string | null;
}

export type ProtectionPatch = Partial<Pick<VhostProtection, 'rateLimit' | 'geoBlock' | 'botDetection'>>;

/**
 * Consequences of tunnelling an app, in the order they bite. Rendered as
 * a list next to the toggle so the switch is never a surprise.
 *
 * These are not hypothetical: an app in the gateway's namespace has no
 * address on aurora_net, so it cannot be reached by name and cannot
 * publish its own ports. Aurora rewrites the compose and the Caddy vhost
 * to cope, but the app does restart, and it does go offline entirely if
 * the tunnel drops.
 */
export function tunnelConsequences(network: PackageNetwork): string[] {
  const out: string[] = [];
  out.push(
    network.containers.length === 1
      ? 'This app restarts.'
      : `${network.containers.length} containers restart.`,
  );
  if (network.publishedPorts.length) {
    out.push(
      `Its ${network.publishedPorts.length === 1 ? 'port' : 'ports'} (${network.publishedPorts.join(', ')}) move onto the gateway, and its web address is re-pointed to follow.`,
    );
  }
  out.push('It loses its own address on the Aurora network, so other apps can no longer reach it by name.');
  out.push('If the tunnel drops, this app has no network at all. That is the kill switch, and it is the point.');
  return out;
}

/** Consequences of moving an app back off the tunnel. */
export function untunnelConsequences(network: PackageNetwork): string[] {
  return [
    network.containers.length === 1 ? 'This app restarts.' : `${network.containers.length} containers restart.`,
    'Its traffic goes out over your normal connection, from your own IP address.',
    'It gets its address on the Aurora network back.',
  ];
}

/** Badge tone for an app's egress state. */
export function egressTone(network: PackageNetwork): 'ok' | 'warn' | 'neutral' {
  if (network.mode === 'direct') return 'neutral';
  return network.gatewayHealthy ? 'ok' : 'warn';
}

export function egressLabel(network: PackageNetwork): string {
  if (network.mode === 'direct') return 'Direct';
  return network.gatewayHealthy ? 'Through the VPN' : 'VPN down';
}

/**
 * Vhosts Aurora thinks are under-protected: reachable from outside, not
 * behind Authelia, and with nothing in front of them. This is the list
 * the Security page acts on.
 */
export function unprotectedVhosts(rows: VhostProtection[]): VhostProtection[] {
  return rows.filter(
    (v) =>
      v.publiclyResolvable &&
      !v.authelia &&
      !v.rateLimit.enabled &&
      !v.geoBlock.enabled &&
      !v.botDetection,
  );
}

/** A one-line summary of what is in front of a vhost. */
export function protectionSummary(v: VhostProtection): string {
  const on: string[] = [];
  if (v.rateLimit.enabled) on.push(`${v.rateLimit.requestsPerMinute}/min`);
  if (v.geoBlock.enabled) {
    on.push(
      v.geoBlock.allowCountries.length
        ? `${v.geoBlock.allowCountries.join(', ')} only`
        : 'everywhere blocked',
    );
  }
  if (v.botDetection) on.push('bot filtering');
  if (v.authelia) on.push('sign-in required');
  return on.length ? on.join(' · ') : 'Nothing in front of it';
}

/** Total requests turned away across every vhost in the last day. */
export function totalBlocked(rows: VhostProtection[]): number {
  return rows.reduce((n, v) => n + v.blocked24h, 0);
}

export const NetworkApi = {
  async get(pkg: string): Promise<PackageNetwork> {
    const { data } = await http.get<PackageNetwork>(`/packages/${encodeURIComponent(pkg)}/network`);
    return data;
  },
  /** Change egress. Returns a job, because containers restart. */
  async setMode(pkg: string, mode: EgressMode): Promise<JobRef> {
    const { data } = await http.put<JobRef>(`/packages/${encodeURIComponent(pkg)}/network`, { mode });
    return data;
  },
  async protection(): Promise<VhostProtection[]> {
    const { data } = await http.get<VhostProtection[]>('/protection');
    return data;
  },
  async setProtection(vhost: string, patch: ProtectionPatch): Promise<VhostProtection> {
    const { data } = await http.put<VhostProtection>(`/protection/${encodeURIComponent(vhost)}`, patch);
    return data;
  },
};
