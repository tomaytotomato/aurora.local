// Log scripts for mock jobs. Each returns the lines the job will stream
// and how it ends, so a JobLogPanel in dev shows something that reads
// like real `docker compose` output rather than lorem ipsum.
//
// Timing matters here: the whole point of the panel is that a slow pull
// stops looking like a hang, so an update deliberately takes a few
// seconds rather than resolving instantly.

import type { JobFailureCode, JobKind } from '@/api/jobs';

import { FAILING_UPDATE_TARGETS } from './updates';

export interface JobScript {
  lines: string[];
  outcome: 'success' | 'failed';
  failureCode: JobFailureCode | null;
  failureReason: string | null;
  exitCode: number;
  /** Delay between lines when streamed. */
  intervalMs: number;
}

function ok(lines: string[], intervalMs = 600): JobScript {
  return { lines, outcome: 'success', failureCode: null, failureReason: null, exitCode: 0, intervalMs };
}

function fails(
  lines: string[],
  failureCode: JobFailureCode,
  failureReason: string,
  intervalMs = 600,
): JobScript {
  return { lines, outcome: 'failed', failureCode, failureReason, exitCode: 1, intervalMs };
}

const project = (target: string | null) => `aurora-${target ?? 'unknown'}`;

export function jobScript(kind: JobKind, target: string | null): JobScript {
  switch (kind) {
    case 'update':
      if (target && FAILING_UPDATE_TARGETS.has(target)) {
        return fails(
          [
            `==> updating ${target}`,
            `Pulling ollama (ollama/ollama:0.4.1)…`,
            'toomanyrequests: You have reached your pull rate limit.',
            'ERROR: failed to pull image, aborting before touching the running stack',
          ],
          'pull_rate_limited',
          "Docker Hub turned the pull away for hitting its rate limit. Aurora stopped before changing anything, so the app is still running on its current version. Try again in an hour, or sign in to Docker Hub.",
          900,
        );
      }
      return ok(
        [
          `==> updating ${target}`,
          `Pulling images for ${project(target)}…`,
          ' 3f4a2c1b: Pulling fs layer',
          ' 3f4a2c1b: Download complete',
          ' 9d81ee0f: Pull complete',
          `Recreating ${project(target)} …`,
          `Container ${project(target)} Started`,
          `Container ${project(target)} Healthy`,
          'Pruning replaced images',
          'Update complete',
        ],
        700,
      );

    case 'update-check':
      return ok(
        [
          'Checking registries for newer images…',
          ' core        caddy:2.8                     up to date',
          ' privacy     adguard/adguardhome:v0.107    v0.107.52 available',
          ' media       linuxserver/*                 2 of 3 behind',
          ' monitoring  louislam/uptime-kuma:1        rebuilt upstream',
          ' jellyfin    jellyfin/jellyfin:10.9.6      10.10.0 available',
          ' backup      kopia/kopia:latest            registry unreachable',
          'Checked 9 packages, 4 with updates waiting',
        ],
        450,
      );

    case 'enable':
      return ok(
        [
          `==> bringing up ${target}`,
          `Creating ${project(target)} …`,
          `Pulling images for ${project(target)}…`,
          ' Pull complete',
          `Container ${project(target)} Created`,
          `Container ${project(target)} Started`,
          'Writing caddy snippet',
          'Reloading caddy',
          `Container ${project(target)} Healthy`,
        ],
        650,
      );

    case 'disable':
      return ok(
        [
          `==> removing ${target}`,
          `Container ${project(target)} Stopping`,
          `Container ${project(target)} Stopped`,
          `Container ${project(target)} Removed`,
          'Removing caddy snippet',
          'Reloading caddy',
          'Data volumes left on disk',
        ],
        450,
      );

    case 'start':
      return ok(
        [
          `==> starting ${target}`,
          `Container ${project(target)} Starting`,
          `Container ${project(target)} Started`,
          `Container ${project(target)} Healthy`,
        ],
        800,
      );

    case 'restart':
      return ok(
        [
          `==> restarting ${target}`,
          `Container ${project(target)} Restarting`,
          `Container ${project(target)} Started`,
        ],
        500,
      );

    case 'backup':
      return ok(
        [
          'Connecting to repository…',
          'Running before-snapshot actions',
          ' immich-postgres: pg_dump … 214 MB',
          ' silverbullet: sqlite checkpoint … done',
          'Snapshotting /data/home/bruce/aurora.local … 1,204 files',
          'Snapshotting /data/photos/library … 94,612 files',
          'Snapshotting /data/documents … 8,431 files',
          'Snapshotting /data/notes … 612 files',
          'Uploading 1.2 GiB (of 412 GiB, rest deduplicated)',
          'Snapshot complete',
        ],
        650,
      );

    case 'restore':
      return ok(
        [
          `Connecting to repository…`,
          `Restoring snapshot ${target}`,
          ' 412 files, 1.8 GiB',
          ' restoring /data/documents … done',
          ' restoring /data/notes … done',
          'Verifying checksums',
          'Restore complete',
        ],
        700,
      );

    case 'deploy':
      return ok(
        [
          `==> deploying custom stack ${target}`,
          'Validating compose file',
          'No port conflicts found',
          `Creating ${target} …`,
          `Container ${target} Started`,
        ],
        650,
      );

    default:
      return ok(['Working…', 'Done'], 500);
  }
}
