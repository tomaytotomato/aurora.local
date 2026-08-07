import { describe, expect, it } from 'vitest';
import {
  dockerStructureFor,
  isCorePackage,
  isRemovable,
  splitByCore,
  type PackageSummary,
} from './packages';

/**
 * Core-vs-non-core and Docker/Compose heuristics behind the Apps/Core
 * split (PackagesList) and the lifecycle action gating (PackageDetail).
 * Both are pure reads off PackageSummary so they're covered here rather
 * than by mounting either view.
 */

function pkg(over: Partial<PackageSummary> & { name: string }): PackageSummary {
  return {
    category: 'productivity',
    description: '',
    enabled: false,
    running: false,
    ...over,
  };
}

describe('isCorePackage / isRemovable', () => {
  it('treats category "core" as core', () => {
    const core = pkg({ name: 'core', category: 'core' });
    expect(isCorePackage(core)).toBe(true);
    expect(isRemovable(core)).toBe(false);
  });

  it('treats every other category as removable', () => {
    const media = pkg({ name: 'media', category: 'media' });
    expect(isCorePackage(media)).toBe(false);
    expect(isRemovable(media)).toBe(true);
  });
});

describe('splitByCore', () => {
  it('separates core from non-core, preserving order within each group', () => {
    const list = [
      pkg({ name: 'core', category: 'core' }),
      pkg({ name: 'privacy', category: 'privacy' }),
      pkg({ name: 'identity', category: 'identity' }),
      pkg({ name: 'media', category: 'media' }),
    ];
    const { core, apps } = splitByCore(list);
    expect(core.map((p) => p.name)).toEqual(['core']);
    expect(apps.map((p) => p.name)).toEqual(['privacy', 'identity', 'media']);
  });

  it('returns empty groups for an empty catalogue', () => {
    expect(splitByCore([])).toEqual({ core: [], apps: [] });
  });
});

describe('dockerStructureFor', () => {
  it('reads as a single container with only the implicit core dependency and one port', () => {
    const p = pkg({ name: 'notes', dependsOn: ['core'], ports: [{ host: 3030, container: 3000 }] });
    expect(dockerStructureFor(p)).toBe('container');
  });

  it('reads as a single container with no dependencies or ports at all', () => {
    const p = pkg({ name: 'bare' });
    expect(dockerStructureFor(p)).toBe('container');
  });

  it('reads as compose when it depends on something beyond core', () => {
    const p = pkg({ name: 'media', dependsOn: ['core', 'privacy'], ports: [{ host: 8989 }] });
    expect(dockerStructureFor(p)).toBe('compose');
  });

  it('reads as compose when it exposes more than one port', () => {
    const p = pkg({ name: 'privacy', dependsOn: ['core'], ports: [{ host: 53 }, { host: 3000 }] });
    expect(dockerStructureFor(p)).toBe('compose');
  });
});
