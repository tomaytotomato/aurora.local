// Marketplace catalogue handlers. In mock mode the feature reads as
// enabled with a small verified catalogue and one pending update, so the
// Settings card and the Overview nudge can be exercised without a backend.
// See docs/MARKETPLACE_HOSTING_PLAN.md.

import { http, HttpResponse } from 'msw';

import type { MarketplaceApp, MarketplaceStatus } from '@/api/marketplace';

const apps: MarketplaceApp[] = [
  {
    slug: 'jellyfin',
    title: 'Jellyfin',
    description: 'Your own media server for films and TV.',
    category: 'media',
    icon: 'jellyfin',
    images: [{ ref: 'lscr.io/linuxserver/jellyfin:latest', digest: null }],
    unpinned: true,
  },
  {
    slug: 'immich',
    title: 'Immich',
    description: 'Self-hosted photo and video backup from your phone.',
    category: 'productivity',
    images: [{ ref: 'ghcr.io/immich-app/immich-server:release', digest: null }],
    unpinned: true,
  },
];

let status: MarketplaceStatus = {
  enabled: true,
  activeVersion: 'v2026.08.20-9c1f2a',
  activeGeneratedAt: '2026-08-20T03:14:00Z',
  appCount: apps.length,
  signatureValid: true,
  source: 'cache',
  lastFetchedAt: '2026-08-28T03:14:00Z',
  lastFetchError: null,
  updateAvailable: true,
  availableVersion: 'v2026.08.28-2ee090',
  availableGeneratedAt: '2026-08-28T03:14:00Z',
  availableAppCount: apps.length + 1,
  availableNewAppCount: 1,
};

export const marketplaceHandlers = [
  http.get('/api/marketplace', () => HttpResponse.json(apps)),
  http.get('/api/marketplace/status', () => HttpResponse.json(status)),
  http.get('/api/marketplace/:slug', ({ params }) => {
    const app = apps.find((a) => a.slug === params.slug);
    if (!app) return new HttpResponse(null, { status: 404 });
    return HttpResponse.json({ ...app, compose: 'name: aurora-mock\n', readme: '# Mock app\n' });
  }),
  http.post('/api/marketplace/refresh', () => HttpResponse.json(status)),
  http.post('/api/marketplace/accept', () => {
    status = {
      ...status,
      activeVersion: status.availableVersion ?? status.activeVersion,
      activeGeneratedAt: status.availableGeneratedAt ?? status.activeGeneratedAt,
      appCount: status.availableAppCount ?? status.appCount,
      source: 'fetch',
      updateAvailable: false,
      availableVersion: null,
      availableGeneratedAt: null,
      availableAppCount: null,
      availableNewAppCount: null,
    };
    return HttpResponse.json(status);
  }),
];
