import { describe, expect, it } from 'vitest';
import {
  dockerStructureFor,
  isCorePackage,
  isRemovable,
  packageLinks,
  splitByCore,
  splitCatalogue,
  type PackageSummary,
} from './packages';

/**
 * Pure logic behind the Apps section: the Apps/Core page split, the
 * Installed/Marketplace catalogue split, the Docker/Compose heuristic,
 * and the source/homepage link guard. All are pure reads off
 * PackageSummary so they're covered here rather than by mounting a view.
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
  it('treats the curated platform set (core, identity, storage) as core and non-removable', () => {
    for (const name of ['core', 'identity', 'storage']) {
      const p = pkg({ name });
      expect(isCorePackage(p)).toBe(true);
      expect(isRemovable(p)).toBe(false);
    }
  });

  it('treats every other app as removable', () => {
    for (const name of ['media', 'photos', 'notes', 'ai']) {
      const p = pkg({ name });
      expect(isCorePackage(p)).toBe(false);
      expect(isRemovable(p)).toBe(true);
    }
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
    // identity is part of the curated core set, so it lands in `core`.
    expect(core.map((p) => p.name)).toEqual(['core', 'identity']);
    expect(apps.map((p) => p.name)).toEqual(['privacy', 'media']);
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

describe('splitCatalogue', () => {
  it('excludes core and partitions the rest on enabled', () => {
    const list = [
      pkg({ name: 'core', category: 'core', enabled: true }),
      pkg({ name: 'media', enabled: true }),
      pkg({ name: 'photos', enabled: false }),
    ];
    const { installed, marketplace } = splitCatalogue(list);
    expect(installed.map((p) => p.name)).toEqual(['media']);
    expect(marketplace.map((p) => p.name)).toEqual(['photos']);
  });

  it('never puts a core package in either half, even if flagged enabled', () => {
    const list = [pkg({ name: 'core', category: 'core', enabled: false })];
    const { installed, marketplace } = splitCatalogue(list);
    expect(installed).toEqual([]);
    expect(marketplace).toEqual([]);
  });

  it('returns empty groups for an empty catalogue', () => {
    expect(splitCatalogue([])).toEqual({ installed: [], marketplace: [] });
  });
});

describe('packageLinks', () => {
  it('returns both links when both URLs are present', () => {
    const p = pkg({ name: 'jellyfin', sourceUrl: 'https://github.com/jellyfin/jellyfin', homepageUrl: 'https://jellyfin.org' });
    expect(packageLinks(p)).toEqual([
      { label: 'Source', url: 'https://github.com/jellyfin/jellyfin' },
      { label: 'Docs', url: 'https://jellyfin.org' },
    ]);
  });

  it('omits Source when sourceUrl is missing', () => {
    const p = pkg({ name: 'x', homepageUrl: 'https://example.com' });
    expect(packageLinks(p)).toEqual([{ label: 'Docs', url: 'https://example.com' }]);
  });

  it('omits Docs when homepageUrl is missing', () => {
    const p = pkg({ name: 'x', sourceUrl: 'https://example.com/repo' });
    expect(packageLinks(p)).toEqual([{ label: 'Source', url: 'https://example.com/repo' }]);
  });

  it('returns an empty array when neither is present', () => {
    expect(packageLinks(pkg({ name: 'x' }))).toEqual([]);
  });

  it('treats a null URL the same as a missing one', () => {
    const p = pkg({ name: 'x', sourceUrl: null, homepageUrl: null });
    expect(packageLinks(p)).toEqual([]);
  });
});
