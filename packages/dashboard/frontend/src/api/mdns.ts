// mDNS alias management API (2026-08-03 v0.3.x productionize).
//
// Backs the Settings "LAN discovery" card. The service publishes one
// A-record per enabled-package vhost so `notes.aurora.local`,
// `code.aurora.local`, etc. resolve on the LAN via avahi.
//
// Endpoints:
//   GET  /api/mdns/aliases    → { aliases, total, up, failed }
//   POST /api/mdns/reconcile  → same shape after a forced republish

import { http } from './client';

export type AliasState = 'up' | 'failed' | 'starting';
export type AliasSource = 'manifest' | 'caddy' | 'unknown';

export interface MdnsAlias {
  /** Full DNS name, e.g. `notes.aurora.local`. */
  alias: string;
  /** Short label, e.g. `notes`. */
  label: string;
  /** Package that declared the vhost, e.g. `notes`. */
  pkg: string;
  /** Where the label came from — a `vhosts:` field in manifest or a caddy.snippet grep. */
  source: AliasSource;
  state: AliasState;
  /** LAN IP the A-record resolves to. */
  targetIp: string;
  /** ISO-8601 UTC — null until the first successful publish. */
  publishedAt: string | null;
  /** Populated when state === 'failed'. */
  error: string | null;
}

export interface MdnsAliasPayload {
  aliases: MdnsAlias[];
  total: number;
  up: number;
  failed: number;
}

export const MdnsApi = {
  async list(): Promise<MdnsAliasPayload> {
    const { data } = await http.get<MdnsAliasPayload>('/mdns/aliases');
    return data;
  },
  /**
   * Force a republish. `toast: false` because the Settings card renders
   * its own success/error state inline — the global 5xx toast would
   * double-announce.
   */
  async reconcile(): Promise<MdnsAliasPayload> {
    const { data } = await http.post<MdnsAliasPayload>('/mdns/reconcile', undefined, {
      toast: false,
    });
    return data;
  },
};
