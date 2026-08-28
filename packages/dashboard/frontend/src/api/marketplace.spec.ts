import { describe, expect, it } from 'vitest';

import { isFullyPinned, provenanceLine, type MarketplaceStatus } from './marketplace';

function status(over: Partial<MarketplaceStatus> = {}): MarketplaceStatus {
  return {
    enabled: true,
    activeVersion: 'v2026.08.28-a3f2c1',
    activeGeneratedAt: '2026-08-28T00:00:00Z',
    appCount: 18,
    signatureValid: true,
    source: 'cache',
    lastFetchedAt: '2026-08-28T03:14:00Z',
    lastFetchError: null,
    updateAvailable: false,
    availableVersion: null,
    availableGeneratedAt: null,
    availableAppCount: null,
    availableNewAppCount: null,
    ...over,
  };
}

describe('isFullyPinned', () => {
  it('is true when no image is unpinned', () => {
    expect(isFullyPinned({ unpinned: false })).toBe(true);
  });
  it('is false when at least one image could not be pinned', () => {
    expect(isFullyPinned({ unpinned: true })).toBe(false);
  });
});

describe('provenanceLine', () => {
  it('summarises a verified catalogue', () => {
    expect(provenanceLine(status())).toBe('Catalogue v2026.08.28-a3f2c1 · 18 apps · verified · from cache');
  });

  it('singularises one app', () => {
    expect(provenanceLine(status({ appCount: 1 }))).toBe('Catalogue v2026.08.28-a3f2c1 · 1 app · verified · from cache');
  });

  it('flags an unverified catalogue rather than hiding it', () => {
    expect(provenanceLine(status({ signatureValid: false }))).toContain('unverified');
  });

  it('handles a box with no catalogue loaded', () => {
    expect(provenanceLine(status({ activeVersion: null }))).toBe('No catalogue loaded');
  });

  it('omits the source clause when unknown', () => {
    expect(provenanceLine(status({ source: null }))).toBe('Catalogue v2026.08.28-a3f2c1 · 18 apps · verified');
  });
});
