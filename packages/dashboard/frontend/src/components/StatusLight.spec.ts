import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import StatusLight from './StatusLight.vue';
import type { StatusLightState } from '@/lib/packageLifecycle';

describe('StatusLight', () => {
  const cases: Array<[StatusLightState, string, boolean]> = [
    ['running', 'Running', true],
    ['stopped', 'Stopped', false],
    ['starting', 'Starting', true],
    ['unhealthy', 'Unhealthy', true],
    ['not-installed', 'Not installed', false],
    ['unknown', 'Unknown', false],
  ];

  it.each(cases)('renders %s with distinguishable copy', (state, label) => {
    const w = mount(StatusLight, { props: { state } });
    expect(w.text()).toContain(label);
    expect(w.attributes('data-status-light')).toBe(state);
  });

  it('gives unknown and not-installed different labels even though both are neutral-toned', () => {
    const unknown = mount(StatusLight, { props: { state: 'unknown' } });
    const notInstalled = mount(StatusLight, { props: { state: 'not-installed' } });
    expect(unknown.text()).not.toBe(notInstalled.text());
  });

  it('renders a coloured dot for running/starting/unhealthy, not for the neutral states', () => {
    for (const [state, , hasDot] of cases) {
      const w = mount(StatusLight, { props: { state } });
      expect(w.find('span[aria-hidden="true"]').exists(), state).toBe(hasDot);
    }
  });
});
