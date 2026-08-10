// Update visibility. Aurora already knows how to update (scripts/update.sh,
// scripts/pin.sh) but the dashboard never said whether an update existed,
// so the only way to find out was to run the script and watch.
//
// This module is read-only: it reports what is current and what is behind.
// Applying an update goes through the existing POST /packages/{name}/upgrade,
// which returns a job id for `JobLogPanel.vue` to stream. There is
// deliberately no second "update" verb on the wire.

import { http } from './client';
import type { JobRef } from './packages';

export type UpdateState = 'current' | 'available' | 'unknown';

export interface ImageUpdate {
  /** Repository without the tag, e.g. "jellyfin/jellyfin". */
  image: string;
  currentTag: string;
  /** null when the running image predates digest recording. */
  currentDigest: string | null;
  latestTag: string | null;
  latestDigest: string | null;
  /**
   * True when compose references this image by digest (via pins.env)
   * rather than by a floating tag. A pinned image can still report an
   * update; it just will not move on its own.
   */
  pinned: boolean;
  state: UpdateState;
}

export interface PackageUpdate {
  /** Package name, matching PackageSummary.name. */
  package: string;
  state: UpdateState;
  images: ImageUpdate[];
  lastCheckedAt: string | null;
  lastUpdatedAt: string | null;
  /** Job id of the most recent update attempt, for linking back to its log. */
  lastUpdateJobId: string | null;
  /** True when that most recent attempt failed. Drives the warning on the card. */
  lastUpdateFailed: boolean;
}

/** Only the packages with an update waiting, in catalogue order. */
export function withUpdates(list: PackageUpdate[]): PackageUpdate[] {
  return list.filter((u) => u.state === 'available');
}

/** How many packages have an update waiting. Feeds the nav badge and the Home strip. */
export function countAvailable(list: PackageUpdate[]): number {
  return withUpdates(list).length;
}

/** Index by package name so a card can look itself up in one pass. */
export function indexByPackage(list: PackageUpdate[]): Record<string, PackageUpdate> {
  const out: Record<string, PackageUpdate> = {};
  for (const u of list) out[u.package] = u;
  return out;
}

/**
 * "10.9.6 → 10.10.0" for a moving tag, or just the current tag when there
 * is nothing to move to. Digest-only changes (same tag, new digest) read
 * as "10.9.6 → new build", which is what actually happened.
 */
export function versionLabel(img: ImageUpdate): string {
  if (img.state !== 'available' || !img.latestTag) return img.currentTag;
  if (img.latestTag === img.currentTag) return `${img.currentTag} → new build`;
  return `${img.currentTag} → ${img.latestTag}`;
}

/** True when every image in the package is digest-pinned. */
export function fullyPinned(u: PackageUpdate): boolean {
  return u.images.length > 0 && u.images.every((i) => i.pinned);
}

export const UpdatesApi = {
  async list(): Promise<PackageUpdate[]> {
    const { data } = await http.get<PackageUpdate[]>('/updates');
    return data;
  },
  async get(name: string): Promise<PackageUpdate> {
    const { data } = await http.get<PackageUpdate>(`/updates/${encodeURIComponent(name)}`);
    return data;
  },
  /** Re-query the registry for every package. Returns a job so the caller can stream progress. */
  async check(): Promise<JobRef> {
    const { data } = await http.post<JobRef>('/updates/check');
    return data;
  },
};
