import { describe, it, expect } from 'vitest';
import { variantLabel, type PackageSummary } from './packages';

/**
 * ESSENCE: "one clear choice per job". The manifests have recorded that
 * choice since long before this test — roundcube is the default webmail,
 * silverbullet the default notes app — but nothing read the fields, so the
 * catalogue presented three webmails as three equal options and left a
 * non-technical owner to choose between them on nothing at all.
 */
function pkg(name: string, extra: Partial<PackageSummary> = {}): PackageSummary {
  return {
    name,
    title: name === 'notes' ? 'Notes (SilverBullet)' : undefined,
    category: 'productivity',
    description: '',
    enabled: false,
    running: false,
    ...extra,
  } as PackageSummary;
}

const CATALOGUE = [
  pkg('roundcube', { title: 'Roundcube (webmail)', variantGroup: 'webmail', variantDefault: true }),
  pkg('snappymail', { title: 'SnappyMail (webmail)', variantGroup: 'webmail' }),
  pkg('bulwark', { title: 'Bulwark (webmail)', variantGroup: 'webmail', variantDefault: false }),
  pkg('notes', { title: 'Notes (SilverBullet)', variantGroup: 'notes', variantDefault: true }),
  pkg('memos', { title: 'Notes (Memos)', variantGroup: 'notes', variantDefault: false }),
  pkg('photos', { title: 'Photos (Immich)' }),
];

describe('variantLabel', () => {
  it('marks the recommended app in a group', () => {
    expect(variantLabel(CATALOGUE[0], CATALOGUE)).toBe('Recommended');
    expect(variantLabel(CATALOGUE[3], CATALOGUE)).toBe('Recommended');
  });

  it('tells you what an alternative is an alternative TO, by name', () => {
    // "Alternative to Roundcube" tells the reader something; "webmail
    // variant" is a group slug leaking out of a manifest.
    // Short name: the titles carry a parenthetical, and "Alternative to
    // Roundcube (webmail)" wrapped to three lines on a card while
    // restating the group the reader can already see.
    expect(variantLabel(CATALOGUE[1], CATALOGUE)).toBe('Alternative to Roundcube');
    expect(variantLabel(CATALOGUE[4], CATALOGUE)).toBe('Alternative to Notes');
  });

  it('says nothing about an app that is the only answer to its question', () => {
    expect(variantLabel(CATALOGUE[5], CATALOGUE)).toBeNull();
  });

  it('degrades honestly when a group has no declared default', () => {
    const orphans = [pkg('a', { variantGroup: 'x' }), pkg('b', { variantGroup: 'x' })];
    expect(variantLabel(orphans[0], orphans)).toBe('Alternative');
  });
});
