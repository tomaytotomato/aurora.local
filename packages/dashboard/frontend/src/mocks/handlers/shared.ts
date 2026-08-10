// Helpers shared by more than one domain handler file.

import { HttpResponse } from 'msw';

import type { PackageSummary } from '@/api/packages';

import { state } from '../state';

export const noContent = () => new HttpResponse(null, { status: 204 });
export const nowIso = () => new Date().toISOString();

/** Apply the live enabled/running mock state onto the catalogue shape. */
export function liveSummary(base: PackageSummary): PackageSummary {
  return {
    ...base,
    enabled: state.enabled.has(base.name),
    running: state.running.has(base.name),
  };
}
