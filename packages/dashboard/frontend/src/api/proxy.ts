// Reverse-proxy routes: exposing a container at a friendly address
// without hand-editing a caddy.snippet.
//
// The file stays the source of truth. Aurora writes a fragment, Caddy's
// existing --watch picks it up, and the fragment is visible in the UI
// before it is written. That is deliberate: Dockge's whole argument is
// that hiding the real config behind a database is what makes these
// tools untrustworthy, and it is a good argument.
//
// Routes generated from a package manifest are read-only here. Editing
// those means editing the package, not patching around it.

import { http } from './client';

export interface ProxyRoute {
  id: string;
  /** Label only: "photos" becomes photos.aurora.local. */
  subdomain: string;
  vhost: string;
  /** Where Caddy forwards to, as container:port. */
  target: string;
  /** Generated from a package manifest, so not editable from here. */
  managed: boolean;
  package: string | null;
  createdAt: string | null;
}

export type ConflictKind = 'vhost-taken' | 'mdns-alias' | 'reserved' | 'target-unreachable';

export interface ProxyConflict {
  kind: ConflictKind;
  message: string;
  /** A conflict that only warrants a warning, rather than blocking. */
  advisory: boolean;
}

export interface ProxyPreview {
  vhost: string;
  /** Exactly what would be appended to caddy.snippet. */
  snippet: string;
  conflicts: ProxyConflict[];
}

export interface ProxyTarget {
  container: string;
  ports: number[];
  package: string | null;
}

/**
 * Labels Aurora will not let you take. `admin` is the dashboard itself
 * and `auth` is Authelia; losing either to a typo locks you out of the
 * box, which is a bad afternoon.
 */
export const RESERVED_SUBDOMAINS: readonly string[] = ['admin', 'auth', 'www', 'localhost'];

/** RFC-1123 label rules, which is what a DNS name can actually be. */
export function validateSubdomain(value: string): string | null {
  const label = value.trim().toLowerCase();
  if (!label) return 'Pick a name for this address.';
  if (label.length > 63) return "That's longer than a DNS label is allowed to be (63 characters).";
  if (!/^[a-z0-9]([a-z0-9-]*[a-z0-9])?$/.test(label)) {
    return 'Use letters, numbers and hyphens only, starting and ending with a letter or number.';
  }
  if (RESERVED_SUBDOMAINS.includes(label)) {
    return `"${label}" is reserved — taking it would lock you out of Aurora itself.`;
  }
  return null;
}

/** Conflicts that must be resolved before the route can be created. */
export function blockingConflicts(conflicts: ProxyConflict[]): ProxyConflict[] {
  return conflicts.filter((c) => !c.advisory);
}

/** Routes the operator added by hand, as opposed to package-generated ones. */
export function customRoutes(routes: ProxyRoute[]): ProxyRoute[] {
  return routes.filter((r) => !r.managed);
}

export const ProxyApi = {
  async routes(): Promise<ProxyRoute[]> {
    const { data } = await http.get<ProxyRoute[]>('/proxy/routes');
    return data;
  },
  /** Containers worth pointing an address at, with the ports they listen on. */
  async targets(): Promise<ProxyTarget[]> {
    const { data } = await http.get<ProxyTarget[]>('/proxy/targets');
    return data;
  },
  /** Dry run: the fragment that would be written, and anything it clashes with. */
  async preview(subdomain: string, target: string): Promise<ProxyPreview> {
    const { data } = await http.post<ProxyPreview>('/proxy/preview', { subdomain, target });
    return data;
  },
  async create(subdomain: string, target: string): Promise<ProxyRoute> {
    const { data } = await http.post<ProxyRoute>('/proxy/routes', { subdomain, target });
    return data;
  },
  async remove(id: string): Promise<void> {
    await http.delete(`/proxy/routes/${encodeURIComponent(id)}`);
  },
};
