import { http } from './client';
import type { PackageCategory } from './packages';

/**
 * The marketplace catalogue — a signed, versioned app index Aurora fetches
 * at runtime, separate from the dashboard's own release. See
 * docs/MARKETPLACE_HOSTING_PLAN.md. The whole surface is read-mostly: the
 * only writes are "fetch the remote index now" and "accept the pending
 * update", and neither touches a running app (plan point 7).
 */

export interface MarketplaceImage {
  ref: string;
  /** Resolved image digest, or null when the composer could not pin one. */
  digest?: string | null;
}

export interface MarketplaceApp {
  slug: string;
  title: string;
  description: string;
  category: PackageCategory;
  icon?: string | null;
  dependsOn?: string[] | null;
  recommends?: string[] | null;
  variantGroup?: string | null;
  variantDefault?: boolean | null;
  sourceUrl?: string | null;
  homepageUrl?: string | null;
  requires?: Record<string, unknown> | null;
  images: MarketplaceImage[];
  /** True when at least one image could not be pinned by digest. */
  unpinned: boolean;
  // Detail-only embedded bodies; absent on the list projection.
  compose?: string;
  envExample?: string;
  caddySnippet?: string;
  readme?: string;
}

export interface MarketplaceStatus {
  enabled: boolean;
  activeVersion?: string | null;
  activeGeneratedAt?: string | null;
  appCount: number;
  signatureValid: boolean;
  /** Where the active catalogue came from: seed, cache, or fetch. */
  source?: string | null;
  lastFetchedAt?: string | null;
  lastFetchError?: string | null;
  updateAvailable: boolean;
  availableVersion?: string | null;
  availableGeneratedAt?: string | null;
  availableAppCount?: number | null;
  /** How many apps in the pending index are new (not in the active one). */
  availableNewAppCount?: number | null;
}

/** True when a marketplace app carries a fully-pinned image set. */
export function isFullyPinned(app: Pick<MarketplaceApp, 'unpinned'>): boolean {
  return !app.unpinned;
}

/**
 * One-line provenance summary for the Settings surface, e.g.
 * "Catalogue v2026.08.28-a3f2c1 · 18 apps · verified · from cache".
 * Kept as a pure function so it can be unit-tested without a component.
 */
export function provenanceLine(s: MarketplaceStatus): string {
  if (!s.activeVersion) return 'No catalogue loaded';
  const parts = [
    `Catalogue ${s.activeVersion}`,
    `${s.appCount} app${s.appCount === 1 ? '' : 's'}`,
    s.signatureValid ? 'verified' : 'unverified',
  ];
  if (s.source) parts.push(`from ${s.source}`);
  return parts.join(' · ');
}

export const MarketplaceApi = {
  async list(): Promise<MarketplaceApp[]> {
    const { data } = await http.get<MarketplaceApp[]>('/marketplace');
    return data;
  },
  async status(): Promise<MarketplaceStatus> {
    const { data } = await http.get<MarketplaceStatus>('/marketplace/status');
    return data;
  },
  async get(slug: string): Promise<MarketplaceApp> {
    const { data } = await http.get<MarketplaceApp>(`/marketplace/${slug}`);
    return data;
  },
  /** Fetch the remote index now; stages a newer verified one as available. */
  async refresh(): Promise<MarketplaceStatus> {
    const { data } = await http.post<MarketplaceStatus>('/marketplace/refresh');
    return data;
  },
  /** Accept the pending update, making it the active catalogue. */
  async accept(): Promise<MarketplaceStatus> {
    const { data } = await http.post<MarketplaceStatus>('/marketplace/accept');
    return data;
  },
};
