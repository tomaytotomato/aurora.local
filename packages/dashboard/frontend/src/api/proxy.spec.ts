import { describe, expect, it } from 'vitest';

import {
  RESERVED_SUBDOMAINS,
  blockingConflicts,
  customRoutes,
  validateSubdomain,
  type ProxyConflict,
  type ProxyRoute,
} from './proxy';

function route(over: Partial<ProxyRoute> & { id: string }): ProxyRoute {
  return {
    subdomain: 'books',
    vhost: 'books.aurora.local',
    target: 'calibre-web:8083',
    managed: false,
    package: null,
    createdAt: null,
    ...over,
  };
}

describe('validateSubdomain', () => {
  it('accepts an ordinary label', () => {
    expect(validateSubdomain('books')).toBeNull();
    expect(validateSubdomain('calibre-web')).toBeNull();
    expect(validateSubdomain('n8n')).toBeNull();
  });

  it('is case-insensitive, since DNS is', () => {
    expect(validateSubdomain('Books')).toBeNull();
  });

  it('refuses the names that would lock you out of Aurora', () => {
    // Losing admin or auth to a typo means losing the dashboard and
    // sign-on respectively, which is a bad afternoon.
    for (const reserved of RESERVED_SUBDOMAINS) {
      expect(validateSubdomain(reserved)).toContain('reserved');
    }
  });

  it('refuses characters DNS will not carry', () => {
    expect(validateSubdomain('my books')).toBeDefined();
    expect(validateSubdomain('books!')).toBeDefined();
    expect(validateSubdomain('books.local')).toBeDefined();
  });

  it('refuses a label starting or ending with a hyphen', () => {
    expect(validateSubdomain('-books')).toBeDefined();
    expect(validateSubdomain('books-')).toBeDefined();
  });

  it('refuses an empty name rather than silently accepting it', () => {
    expect(validateSubdomain('')).toBeDefined();
    expect(validateSubdomain('   ')).toBeDefined();
  });

  it('enforces the 63-character DNS label limit', () => {
    expect(validateSubdomain('a'.repeat(63))).toBeNull();
    expect(validateSubdomain('a'.repeat(64))).toContain('63');
  });
});

describe('blockingConflicts', () => {
  const conflict = (over: Partial<ProxyConflict>): ProxyConflict => ({
    kind: 'vhost-taken',
    message: 'taken',
    advisory: false,
    ...over,
  });

  it('separates what must be fixed from what is merely worth saying', () => {
    const all = [
      conflict({ kind: 'vhost-taken', advisory: false }),
      conflict({ kind: 'mdns-alias', advisory: true }),
      conflict({ kind: 'target-unreachable', advisory: true }),
    ];
    expect(blockingConflicts(all)).toHaveLength(1);
    expect(blockingConflicts(all)[0].kind).toBe('vhost-taken');
  });

  it('lets a route be created when a container is not up yet', () => {
    // Writing a route for something that has not started is legitimate:
    // it starts working when the container appears.
    const all = [conflict({ kind: 'target-unreachable', advisory: true })];
    expect(blockingConflicts(all)).toEqual([]);
  });

  it('returns nothing for a clean preview', () => {
    expect(blockingConflicts([])).toEqual([]);
  });
});

describe('customRoutes', () => {
  it('separates hand-added routes from the ones apps bring with them', () => {
    const rows = [
      route({ id: 'a', managed: true, package: 'photos' }),
      route({ id: 'b', managed: false }),
    ];
    expect(customRoutes(rows).map((r) => r.id)).toEqual(['b']);
  });

  it('returns nothing on a box where every route came from a package', () => {
    expect(customRoutes([route({ id: 'a', managed: true })])).toEqual([]);
  });
});
