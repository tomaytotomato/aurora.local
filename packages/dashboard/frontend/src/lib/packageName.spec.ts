import { describe, expect, it } from 'vitest';
import { categoryLabel, packageLabel, prettyPackageName } from './packageName';

describe('prettyPackageName', () => {
  it('title-cases a plain slug', () => {
    expect(prettyPackageName('media')).toBe('Media');
  });

  it('title-cases each word of a hyphenated slug', () => {
    expect(prettyPackageName('home-automation')).toBe('Home Automation');
  });

  it('uppercases known acronyms instead of title-casing them', () => {
    expect(prettyPackageName('ai')).toBe('AI');
    expect(prettyPackageName('vpn')).toBe('VPN');
  });
});

describe('packageLabel', () => {
  it('prefers an explicit title over the prettified slug', () => {
    expect(packageLabel({ name: 'notes', title: 'Notes (SilverBullet)' })).toBe('Notes (SilverBullet)');
  });

  it('falls back to the prettified slug when title is absent', () => {
    expect(packageLabel({ name: 'home-automation' })).toBe('Home Automation');
  });

  it('falls back to the prettified slug when title is blank', () => {
    expect(packageLabel({ name: 'ai', title: '   ' })).toBe('AI');
  });
});

describe('categoryLabel', () => {
  // Regression: the picker's tab-label logic used to hand-roll its own
  // regex title-caser (`c.replace('-', ' ').replace(/\b\w/g, ...)`), which
  // rendered the `ai` category as "Ai" instead of "AI", and the catalogue's
  // category eyebrow printed the raw slug (`home-automation`) verbatim.
  // categoryLabel is the same prettifier as packageLabel's fallback, reused
  // rather than re-derived, so both drift the same way if it ever changes.
  it('is the same prettifier used for package names', () => {
    expect(categoryLabel).toBe(prettyPackageName);
  });

  it('renders the ai category as the acronym, not title-case', () => {
    expect(categoryLabel('ai')).toBe('AI');
  });

  it('renders a hyphenated category as separate title-cased words', () => {
    expect(categoryLabel('home-automation')).toBe('Home Automation');
  });
});
