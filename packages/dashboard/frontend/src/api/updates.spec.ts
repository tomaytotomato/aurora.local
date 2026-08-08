import { describe, expect, it } from 'vitest';

import {
  countAvailable,
  fullyPinned,
  indexByPackage,
  versionLabel,
  withUpdates,
  type ImageUpdate,
  type PackageUpdate,
} from './updates';

function image(over: Partial<ImageUpdate> = {}): ImageUpdate {
  return {
    image: 'example/app',
    currentTag: '1.0.0',
    currentDigest: 'sha256:aaaa',
    latestTag: '1.0.0',
    latestDigest: 'sha256:aaaa',
    pinned: false,
    state: 'current',
    ...over,
  };
}

function update(over: Partial<PackageUpdate> & { package: string }): PackageUpdate {
  return {
    state: 'current',
    images: [image()],
    lastCheckedAt: '2026-08-08T06:15:00Z',
    lastUpdatedAt: null,
    lastUpdateJobId: null,
    lastUpdateFailed: false,
    ...over,
  };
}

describe('withUpdates / countAvailable', () => {
  it('counts only the packages actually behind', () => {
    const list = [
      update({ package: 'core' }),
      update({ package: 'media', state: 'available' }),
      update({ package: 'jellyfin', state: 'available' }),
      update({ package: 'backup', state: 'unknown' }),
    ];
    expect(withUpdates(list).map((u) => u.package)).toEqual(['media', 'jellyfin']);
    expect(countAvailable(list)).toBe(2);
  });

  it('does not count an unknown check as an update, because it is not one', () => {
    expect(countAvailable([update({ package: 'backup', state: 'unknown' })])).toBe(0);
  });

  it('returns zero for an empty list', () => {
    expect(countAvailable([])).toBe(0);
    expect(withUpdates([])).toEqual([]);
  });
});

describe('indexByPackage', () => {
  it('keys rows by package name so a card can look itself up', () => {
    const media = update({ package: 'media', state: 'available' });
    expect(indexByPackage([update({ package: 'core' }), media]).media).toBe(media);
  });

  it('returns an empty object for an empty list', () => {
    expect(indexByPackage([])).toEqual({});
  });
});

describe('versionLabel', () => {
  it('shows the move when the tag changes', () => {
    expect(versionLabel(image({ state: 'available', currentTag: '10.9.6', latestTag: '10.10.0' }))).toBe(
      '10.9.6 → 10.10.0',
    );
  });

  it('says "new build" when the tag is unchanged but the digest moved', () => {
    const img = image({
      state: 'available',
      currentTag: '1',
      latestTag: '1',
      currentDigest: 'sha256:aaaa',
      latestDigest: 'sha256:bbbb',
    });
    expect(versionLabel(img)).toBe('1 → new build');
  });

  it('shows the current tag alone when nothing is waiting', () => {
    expect(versionLabel(image({ currentTag: '2.8' }))).toBe('2.8');
  });

  it('shows the current tag alone when the check failed, rather than inventing a target', () => {
    expect(versionLabel(image({ state: 'unknown', currentTag: 'latest', latestTag: null }))).toBe('latest');
  });
});

describe('fullyPinned', () => {
  it('is true only when every image is digest-pinned', () => {
    expect(fullyPinned(update({ package: 'a', images: [image({ pinned: true })] }))).toBe(true);
    expect(
      fullyPinned(update({ package: 'a', images: [image({ pinned: true }), image({ pinned: false })] })),
    ).toBe(false);
  });

  it('is false when there are no images, so an empty package never reads as pinned', () => {
    expect(fullyPinned(update({ package: 'a', images: [] }))).toBe(false);
  });
});
