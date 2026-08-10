import { describe, expect, it } from 'vitest';

import {
  hardeningItems,
  hardeningTone,
  outstanding,
  pinningPct,
  type HardeningState,
} from './hardening';

function state(over: Partial<HardeningState> = {}): HardeningState {
  return {
    pinning: { total: 34, pinned: 18, unpinned: ['a:latest'], pinsFileExists: true, generatedAt: null },
    secrets: { encrypted: false, method: null, envFiles: 11, encryptedFiles: 0 },
    dockerSocket: { proxied: false, exposedContainers: ['aurora-dashboard'], writable: true },
    ...over,
  };
}

function byId(s: HardeningState, id: string) {
  return hardeningItems(s).find((i) => i.id === id)!;
}

describe('pinningPct', () => {
  it('reports coverage as a percentage', () => {
    expect(pinningPct({ total: 34, pinned: 17, unpinned: [], pinsFileExists: true, generatedAt: null })).toBe(50);
  });

  it('is null on a box with no images rather than dividing by zero', () => {
    expect(pinningPct({ total: 0, pinned: 0, unpinned: [], pinsFileExists: false, generatedAt: null })).toBeNull();
  });
});

describe('hardeningItems', () => {
  it('returns the three decisions, pinning first', () => {
    expect(hardeningItems(state()).map((i) => i.id)).toEqual(['pinning', 'secrets', 'docker-socket']);
  });

  it('gives every item a reason, not just a status', () => {
    for (const item of hardeningItems(state())) {
      expect(item.rationale.length).toBeGreaterThan(20);
      expect(item.detail.length).toBeGreaterThan(0);
    }
  });

  it('calls partial pinning partial, and quotes the real numbers', () => {
    const item = byId(state(), 'pinning');
    expect(item.status).toBe('partial');
    expect(item.detail).toContain('18 of 34');
    expect(item.detail).toContain('53%');
  });

  it('calls a box with no pins.env todo, not partial', () => {
    const s = state({
      pinning: { total: 34, pinned: 0, unpinned: [], pinsFileExists: false, generatedAt: null },
    });
    expect(byId(s, 'pinning').status).toBe('todo');
    expect(byId(s, 'pinning').detail).toContain('No pins.env');
  });

  it('calls fully pinned done', () => {
    const s = state({
      pinning: { total: 34, pinned: 34, unpinned: [], pinsFileExists: true, generatedAt: null },
    });
    expect(byId(s, 'pinning').status).toBe('done');
  });

  it('treats half-encrypted secrets as partial and none as todo', () => {
    expect(byId(state(), 'secrets').status).toBe('todo');
    const half = state({ secrets: { encrypted: false, method: 'sops-age', envFiles: 11, encryptedFiles: 4 } });
    expect(byId(half, 'secrets').status).toBe('partial');
    const done = state({ secrets: { encrypted: true, method: 'sops-age', envFiles: 11, encryptedFiles: 11 } });
    expect(byId(done, 'secrets').status).toBe('done');
  });

  it('treats a read-write socket mount as worse than a read-only one', () => {
    // Both are bad; one is an open door and the other is a window.
    expect(byId(state(), 'docker-socket').status).toBe('todo');
    const ro = state({
      dockerSocket: { proxied: false, exposedContainers: ['forgejo-runner'], writable: false },
    });
    expect(byId(ro, 'docker-socket').status).toBe('partial');
    const proxied = state({ dockerSocket: { proxied: true, exposedContainers: [], writable: false } });
    expect(byId(proxied, 'docker-socket').status).toBe('done');
  });
});

describe('hardeningTone', () => {
  it('maps status onto the badge vocabulary without flattering anything', () => {
    expect(hardeningTone('done')).toBe('ok');
    expect(hardeningTone('partial')).toBe('warn');
    expect(hardeningTone('todo')).toBe('err');
  });
});

describe('outstanding', () => {
  it('counts what is left', () => {
    expect(outstanding(hardeningItems(state()))).toHaveLength(3);
  });

  it('is empty on a fully hardened box', () => {
    const s = state({
      pinning: { total: 34, pinned: 34, unpinned: [], pinsFileExists: true, generatedAt: null },
      secrets: { encrypted: true, method: 'sops-age', envFiles: 11, encryptedFiles: 11 },
      dockerSocket: { proxied: true, exposedContainers: [], writable: false },
    });
    expect(outstanding(hardeningItems(s))).toEqual([]);
  });
});
