// Update state per package. Deliberately a mixed bag so every branch of
// the update UI is reachable without touching a registry:
//
//   core        current, digest-pinned          → "Up to date", pin badge
//   privacy     update available, unpinned      → the ordinary case
//   media       update available, three images  → multi-image breakdown
//   monitoring  same tag, new digest            → "1 → new build"
//   jellyfin    tag bump                        → "10.9.6 → 10.10.0"
//   ai          available, last attempt FAILED  → the failure banner
//   backup      unknown                         → registry unreachable
//
// EDIT ME to reach a state you want to look at. Flipping a package's
// `state` to 'available' is enough to make its badge appear everywhere.

import type { PackageUpdate } from '@/api/updates';

const CHECKED = '2026-08-08T06:15:00Z';

export function initialUpdates(): Record<string, PackageUpdate> {
  const list: PackageUpdate[] = [
    {
      package: 'core',
      state: 'current',
      images: [
        {
          image: 'caddy',
          currentTag: '2.8',
          currentDigest: 'sha256:9f1e...c22a',
          latestTag: '2.8',
          latestDigest: 'sha256:9f1e...c22a',
          pinned: true,
          state: 'current',
        },
      ],
      lastCheckedAt: CHECKED,
      lastUpdatedAt: '2026-07-02T21:10:00Z',
      lastUpdateJobId: null,
      lastUpdateFailed: false,
    },
    {
      package: 'privacy',
      state: 'available',
      images: [
        {
          image: 'adguard/adguardhome',
          currentTag: 'v0.107',
          currentDigest: 'sha256:41bd...7710',
          latestTag: 'v0.107.52',
          latestDigest: 'sha256:88c0...19fe',
          pinned: false,
          state: 'available',
        },
      ],
      lastCheckedAt: CHECKED,
      lastUpdatedAt: '2026-06-19T08:02:00Z',
      lastUpdateJobId: null,
      lastUpdateFailed: false,
    },
    {
      package: 'media',
      state: 'available',
      images: [
        {
          image: 'lscr.io/linuxserver/prowlarr',
          currentTag: 'latest',
          currentDigest: 'sha256:2ab1...0f4d',
          latestTag: 'latest',
          latestDigest: 'sha256:7c39...aa10',
          pinned: false,
          state: 'available',
        },
        {
          image: 'lscr.io/linuxserver/sonarr',
          currentTag: 'latest',
          currentDigest: 'sha256:5de2...9931',
          latestTag: 'latest',
          latestDigest: 'sha256:e004...31b7',
          pinned: false,
          state: 'available',
        },
        {
          image: 'lscr.io/linuxserver/radarr',
          currentTag: 'latest',
          currentDigest: 'sha256:aa77...4410',
          latestTag: 'latest',
          latestDigest: 'sha256:aa77...4410',
          pinned: false,
          state: 'current',
        },
      ],
      lastCheckedAt: CHECKED,
      lastUpdatedAt: '2026-07-28T19:44:00Z',
      lastUpdateJobId: null,
      lastUpdateFailed: false,
    },
    {
      package: 'storage',
      state: 'current',
      images: [
        {
          image: 'dperson/samba',
          currentTag: 'latest',
          currentDigest: 'sha256:6611...b0c2',
          latestTag: 'latest',
          latestDigest: 'sha256:6611...b0c2',
          pinned: false,
          state: 'current',
        },
      ],
      lastCheckedAt: CHECKED,
      lastUpdatedAt: '2026-05-30T12:00:00Z',
      lastUpdateJobId: null,
      lastUpdateFailed: false,
    },
    {
      package: 'monitoring',
      state: 'available',
      images: [
        {
          image: 'louislam/uptime-kuma',
          currentTag: '1',
          currentDigest: 'sha256:3f0a...5521',
          // Same tag, new digest: upstream rebuilt the image. Reads as
          // "1 → new build" rather than a version bump, because that is
          // honestly what changed.
          latestTag: '1',
          latestDigest: 'sha256:b7e4...ce09',
          pinned: false,
          state: 'available',
        },
        {
          image: 'grafana/grafana',
          currentTag: '11.1.0',
          currentDigest: 'sha256:1c2d...7f31',
          latestTag: '11.1.0',
          latestDigest: 'sha256:1c2d...7f31',
          pinned: true,
          state: 'current',
        },
      ],
      lastCheckedAt: CHECKED,
      lastUpdatedAt: '2026-07-11T07:30:00Z',
      lastUpdateJobId: null,
      lastUpdateFailed: false,
    },
    {
      package: 'jellyfin',
      state: 'available',
      images: [
        {
          image: 'jellyfin/jellyfin',
          currentTag: '10.9.6',
          currentDigest: 'sha256:d41c...8802',
          latestTag: '10.10.0',
          latestDigest: 'sha256:0aa9...4e17',
          pinned: false,
          state: 'available',
        },
      ],
      lastCheckedAt: CHECKED,
      lastUpdatedAt: null,
      lastUpdateJobId: null,
      lastUpdateFailed: false,
    },
    {
      package: 'ai',
      state: 'available',
      images: [
        {
          image: 'ollama/ollama',
          currentTag: '0.3.6',
          currentDigest: 'sha256:77aa...1c40',
          latestTag: '0.4.1',
          latestDigest: 'sha256:9920...ab3e',
          pinned: false,
          state: 'available',
        },
      ],
      lastCheckedAt: CHECKED,
      lastUpdatedAt: '2026-06-01T10:15:00Z',
      // The previous attempt hit Docker Hub's anonymous pull limit. The
      // card surfaces this so a second click isn't a mystery.
      lastUpdateJobId: 'job-update-ai-prior',
      lastUpdateFailed: true,
    },
    {
      package: 'photos',
      state: 'current',
      images: [
        {
          image: 'ghcr.io/immich-app/immich-server',
          currentTag: 'v1.108.0',
          currentDigest: 'sha256:4b21...d0a8',
          latestTag: 'v1.108.0',
          latestDigest: 'sha256:4b21...d0a8',
          pinned: true,
          state: 'current',
        },
      ],
      lastCheckedAt: CHECKED,
      lastUpdatedAt: '2026-08-01T22:05:00Z',
      lastUpdateJobId: null,
      lastUpdateFailed: false,
    },
    {
      package: 'backup',
      // The registry check itself failed, which is not the same as
      // "up to date". The UI says so rather than showing a green tick.
      state: 'unknown',
      images: [
        {
          image: 'kopia/kopia',
          currentTag: 'latest',
          currentDigest: 'sha256:cc10...7ba2',
          latestTag: null,
          latestDigest: null,
          pinned: false,
          state: 'unknown',
        },
      ],
      lastCheckedAt: CHECKED,
      lastUpdatedAt: null,
      lastUpdateJobId: null,
      lastUpdateFailed: false,
    },
  ];

  const out: Record<string, PackageUpdate> = {};
  for (const u of list) out[u.package] = u;
  return out;
}

/**
 * Packages whose update job fails when run. `ai` is the built-in example
 * (Docker Hub rate limit); add a name here to exercise a failure on
 * another package.
 */
export const FAILING_UPDATE_TARGETS: ReadonlySet<string> = new Set(['ai']);
