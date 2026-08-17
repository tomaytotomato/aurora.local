import { describe, it, expect } from 'vitest';
import { deriveStatusLight, isInstalledView, packageActionSlots, type ActionSlot } from './packageLifecycle';

function find(slots: ActionSlot[], action: ActionSlot['action']): ActionSlot {
  const slot = slots.find((s) => s.action === action);
  if (!slot) throw new Error(`no slot for ${action}`);
  return slot;
}

describe('packageActionSlots', () => {
  it('a core package can do none of the four actions', () => {
    const slots = packageActionSlots({ isCore: true, enabled: true, running: true });
    for (const slot of slots) {
      expect(slot.visible).toBe(false);
      expect(slot.reason).toBeTruthy();
    }
  });

  it('a core package stays locked regardless of its enabled/running flags', () => {
    // Core packages report enabled/running like anything else on the
    // wire; isCore alone must be what locks the panel.
    const slots = packageActionSlots({ isCore: true, enabled: false, running: false });
    expect(slots.every((s) => !s.visible)).toBe(true);
  });

  it('a not-installed app can only Install', () => {
    const slots = packageActionSlots({ isCore: false, enabled: false, running: false });
    expect(find(slots, 'install')).toMatchObject({ visible: true, enabled: true });
    expect(find(slots, 'start').visible).toBe(false);
    expect(find(slots, 'disable').visible).toBe(false);
    expect(find(slots, 'uninstall').visible).toBe(false);
  });

  it('a stopped-but-installed app can Start and Uninstall but not Install', () => {
    const slots = packageActionSlots({ isCore: false, enabled: true, running: false });
    expect(find(slots, 'install').visible).toBe(false);
    expect(find(slots, 'start')).toMatchObject({ visible: true, enabled: true });
    expect(find(slots, 'disable').visible).toBe(false);
    expect(find(slots, 'uninstall')).toMatchObject({ visible: true, enabled: true });
  });

  it('a running app can Disable but not Start or Install', () => {
    const slots = packageActionSlots({ isCore: false, enabled: true, running: true });
    expect(find(slots, 'install').visible).toBe(false);
    expect(find(slots, 'start').visible).toBe(false);
    expect(find(slots, 'disable')).toMatchObject({ visible: true, enabled: true });
  });

  it('disable is not shown at all once the app is already stopped', () => {
    const slots = packageActionSlots({ isCore: false, enabled: true, running: false });
    expect(find(slots, 'disable').visible).toBe(false);
  });

  it('a running app can still Uninstall directly, matching the existing disable() behaviour', () => {
    const slots = packageActionSlots({ isCore: false, enabled: true, running: true });
    expect(find(slots, 'uninstall')).toMatchObject({ visible: true, enabled: true });
  });

  it('every visible-but-disabled slot carries a reason', () => {
    for (const enabled of [true, false]) {
      for (const running of [true, false]) {
        for (const isCore of [true, false]) {
          const slots = packageActionSlots({ isCore, enabled, running });
          for (const slot of slots) {
            if (slot.visible && !slot.enabled) {
              expect(slot.reason, `${slot.action} isCore=${isCore} enabled=${enabled} running=${running}`).toBeTruthy();
            }
          }
        }
      }
    }
  });
});

describe('isInstalledView', () => {
  it('is the preview half for a not-installed, non-core package', () => {
    expect(isInstalledView({ isCore: false, enabled: false })).toBe(false);
  });

  it('is the installed half once the app is enabled', () => {
    expect(isInstalledView({ isCore: false, enabled: true })).toBe(true);
  });

  it('a core package is always the installed half, whatever its enabled flag says', () => {
    expect(isInstalledView({ isCore: true, enabled: false })).toBe(true);
    expect(isInstalledView({ isCore: true, enabled: true })).toBe(true);
  });
});

describe('deriveStatusLight', () => {
  it('is unknown before the detail fetch has resolved, regardless of other inputs', () => {
    expect(deriveStatusLight({ loaded: false, enabled: true, running: true, probeState: 'running' })).toBe('unknown');
    expect(deriveStatusLight({ loaded: false, enabled: false, running: false })).toBe('unknown');
  });

  it('is not-installed once loaded when the app is not enabled', () => {
    expect(deriveStatusLight({ loaded: true, enabled: false, running: false })).toBe('not-installed');
  });

  it('is running when enabled+running and no probe data is available', () => {
    expect(deriveStatusLight({ loaded: true, enabled: true, running: true })).toBe('running');
  });

  it('is stopped when enabled but not running and no probe data is available', () => {
    expect(deriveStatusLight({ loaded: true, enabled: true, running: false })).toBe('stopped');
  });

  it('reflects the live probe state when one is available', () => {
    expect(deriveStatusLight({ loaded: true, enabled: true, running: true, probeState: 'starting' })).toBe('starting');
    expect(deriveStatusLight({ loaded: true, enabled: true, running: true, probeState: 'failed' })).toBe('unhealthy');
    expect(deriveStatusLight({ loaded: true, enabled: true, running: true, probeState: 'needs-config' })).toBe('unhealthy');
  });

  it('trusts the plain running boolean over an optimistic/pessimistic not-started probe', () => {
    expect(deriveStatusLight({ loaded: true, enabled: true, running: true, probeState: 'not-started' })).toBe('running');
    expect(deriveStatusLight({ loaded: true, enabled: true, running: false, probeState: 'not-started' })).toBe('stopped');
  });
});
