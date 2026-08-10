// Per-package resource ceilings and live usage.
//
// Mirrors the `resources:` block now in the manifests. The heavy
// packages carry a cap; small well-behaved ones do not, and the UI says
// "uncapped" rather than inventing a number.
//
// `ai` is deliberately close to its ceiling so the usage bar has
// something to show, since that is the package the cap was written for.

import type { PackageResources } from '@/api/packages';

const DEFAULTS: Record<string, { mem: number; cpus: number }> = {
  ai: { mem: 10240, cpus: 3.0 },
  photos: { mem: 6144, cpus: 3.0 },
  documents: { mem: 3072, cpus: 2.0 },
  media: { mem: 4096, cpus: 3.0 },
  monitoring: { mem: 2048, cpus: 1.5 },
  jellyfin: { mem: 4096, cpus: 3.0 },
};

const LIVE: Record<string, { mem: number; cpu: number }> = {
  ai: { mem: 9100, cpu: 212 },
  photos: { mem: 1840, cpu: 14 },
  media: { mem: 1120, cpu: 22 },
  monitoring: { mem: 610, cpu: 6 },
  core: { mem: 92, cpu: 1 },
  privacy: { mem: 148, cpu: 2 },
};

export function resourcesFor(pkg: string): PackageResources {
  const declared = DEFAULTS[pkg];
  const live = LIVE[pkg];
  return {
    package: pkg,
    defaultMemLimitMb: declared?.mem ?? null,
    defaultCpus: declared?.cpus ?? null,
    memLimitMb: null,
    cpus: null,
    memUsedMb: live?.mem ?? null,
    cpuPct: live?.cpu ?? null,
  };
}
