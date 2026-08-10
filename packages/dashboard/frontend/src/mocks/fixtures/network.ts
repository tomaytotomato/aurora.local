// Egress mode per app, and edge protection per vhost.
//
// The interesting rows out of the box:
//   media       already tunnelled (qBittorrent egresses via gluetun today)
//   privacy     locked — it *is* the gateway, so it cannot tunnel itself
//   core        locked — the reverse proxy has to be reachable directly
//   photos      exposed to the internet with nothing in front of it
//
// That last one is the row the Security page exists to nag about.

import type { PackageNetwork, VhostProtection } from '@/api/network';

const LOCKED: Record<string, string> = {
  core: 'Caddy has to answer on the LAN directly, so it can never be tunnelled.',
  privacy: 'This package provides the gateway. It cannot route through itself.',
  identity: 'Sign-on has to be reachable directly, or nothing else can authenticate.',
};

const TUNNELLED = new Set(['media']);

const PORTS: Record<string, number[]> = {
  media: [8080, 8989, 7878],
  photos: [2283],
  notes: [3030],
  documents: [8010],
  ai: [11434],
  git: [3002],
  jellyfin: [8096],
};

const CONTAINERS: Record<string, string[]> = {
  media: ['prowlarr', 'sonarr', 'radarr', 'qbittorrent'],
  photos: ['immich-server', 'immich-machine-learning', 'immich-postgres'],
  documents: ['paperless', 'paperless-db', 'stirling-pdf'],
};

export function networkFor(pkg: string): PackageNetwork {
  const tunnelled = TUNNELLED.has(pkg);
  return {
    package: pkg,
    mode: tunnelled ? 'vpn' : 'direct',
    gateway: 'gluetun',
    locked: pkg in LOCKED,
    lockedReason: LOCKED[pkg] ?? null,
    containers: CONTAINERS[pkg] ?? [pkg],
    publishedPorts: PORTS[pkg] ?? [],
    // What the outside sees: the tunnel exit for a tunnelled app, the
    // home connection for everything else.
    egressIp: tunnelled ? '185.107.56.212' : '81.132.44.19',
    egressCountry: tunnelled ? 'NL' : 'GB',
    gatewayHealthy: true,
  };
}

export function initialProtection(): VhostProtection[] {
  return [
    {
      vhost: 'photos.aurora.local',
      package: 'photos',
      // Exposed to the world with nothing in front of it. The point of
      // the whole Security card.
      publiclyResolvable: true,
      authelia: false,
      rateLimit: { enabled: false, requestsPerMinute: 60 },
      geoBlock: { enabled: false, allowCountries: [] },
      botDetection: false,
      blocked24h: 0,
      lastBlockedAt: null,
    },
    {
      vhost: 'notes.aurora.local',
      package: 'notes',
      publiclyResolvable: true,
      authelia: true,
      rateLimit: { enabled: true, requestsPerMinute: 120 },
      geoBlock: { enabled: true, allowCountries: ['GB', 'IE'] },
      botDetection: true,
      blocked24h: 1_284,
      lastBlockedAt: new Date(Date.now() - 22 * 60_000).toISOString(),
    },
    {
      vhost: 'paperless.aurora.local',
      package: 'documents',
      publiclyResolvable: false,
      authelia: true,
      rateLimit: { enabled: false, requestsPerMinute: 60 },
      geoBlock: { enabled: false, allowCountries: [] },
      botDetection: false,
      blocked24h: 0,
      lastBlockedAt: null,
    },
    {
      vhost: 'grafana.aurora.local',
      package: 'monitoring',
      publiclyResolvable: false,
      authelia: true,
      rateLimit: { enabled: false, requestsPerMinute: 60 },
      geoBlock: { enabled: false, allowCountries: [] },
      botDetection: false,
      blocked24h: 0,
      lastBlockedAt: null,
    },
    {
      vhost: 'jellyfin.aurora.local',
      package: 'jellyfin',
      publiclyResolvable: true,
      // Jellyfin deliberately sits outside Authelia (native clients break
      // under forward-auth), which makes edge protection matter more here
      // than anywhere else.
      authelia: false,
      rateLimit: { enabled: true, requestsPerMinute: 300 },
      geoBlock: { enabled: true, allowCountries: ['GB'] },
      botDetection: true,
      blocked24h: 412,
      lastBlockedAt: new Date(Date.now() - 3 * 3_600_000).toISOString(),
    },
  ];
}
