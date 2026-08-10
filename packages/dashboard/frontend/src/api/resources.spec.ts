import { describe, expect, it } from 'vitest';

import {
  effectiveCpus,
  effectiveMemLimitMb,
  hasResourceOverride,
  memHeadroomPct,
  type PackageResources,
} from './packages';

function resources(over: Partial<PackageResources> = {}): PackageResources {
  return {
    package: 'ai',
    defaultMemLimitMb: 10240,
    defaultCpus: 3,
    memLimitMb: null,
    cpus: null,
    memUsedMb: 9100,
    cpuPct: 212,
    ...over,
  };
}

describe('effective limits', () => {
  it('uses the manifest default when nothing is overridden', () => {
    expect(effectiveMemLimitMb(resources())).toBe(10240);
    expect(effectiveCpus(resources())).toBe(3);
  });

  it('prefers the operator override when there is one', () => {
    const r = resources({ memLimitMb: 4096, cpus: 1.5 });
    expect(effectiveMemLimitMb(r)).toBe(4096);
    expect(effectiveCpus(r)).toBe(1.5);
  });

  it('reports uncapped as null rather than inventing a ceiling', () => {
    const r = resources({ defaultMemLimitMb: null, defaultCpus: null });
    expect(effectiveMemLimitMb(r)).toBeNull();
    expect(effectiveCpus(r)).toBeNull();
  });

  it('lets an override cap a package the manifest left uncapped', () => {
    const r = resources({ defaultMemLimitMb: null, memLimitMb: 2048 });
    expect(effectiveMemLimitMb(r)).toBe(2048);
  });
});

describe('hasResourceOverride', () => {
  it('is false on a package running its shipped defaults', () => {
    expect(hasResourceOverride(resources())).toBe(false);
  });

  it('is true when either field has been changed', () => {
    expect(hasResourceOverride(resources({ memLimitMb: 512 }))).toBe(true);
    expect(hasResourceOverride(resources({ cpus: 0.5 }))).toBe(true);
  });
});

describe('memHeadroomPct', () => {
  it('reports how close the package is to its ceiling', () => {
    expect(memHeadroomPct(resources({ memUsedMb: 5120, defaultMemLimitMb: 10240 }))).toBe(50);
  });

  it('measures against the override, not the shipped default', () => {
    const r = resources({ memUsedMb: 1024, defaultMemLimitMb: 10240, memLimitMb: 2048 });
    expect(memHeadroomPct(r)).toBe(50);
  });

  it('clamps rather than reporting over 100 when a container is over its limit', () => {
    expect(memHeadroomPct(resources({ memUsedMb: 20000, defaultMemLimitMb: 10240 }))).toBe(100);
  });

  it('is null when there is no ceiling to measure against', () => {
    expect(memHeadroomPct(resources({ defaultMemLimitMb: null }))).toBeNull();
  });

  it('is null when the package is not running, rather than showing an empty bar', () => {
    expect(memHeadroomPct(resources({ memUsedMb: null }))).toBeNull();
  });
});
