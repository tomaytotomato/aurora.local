import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import Skeleton from './Skeleton.vue';

/**
 * C10 iter-14: shadcn-vue Skeleton smoke tests. Pins the shadcn
 * defaults (animate-pulse / rounded-md / bg-muted) + confirms caller
 * class merges (width/height/radius overrides). Also asserts
 * aria-hidden so screen readers don't announce the placeholder as
 * empty content while data loads.
 */
describe('Skeleton', () => {
  it('renders with the shadcn defaults', () => {
    const w = mount(Skeleton);
    const cls = w.classes().join(' ');
    expect(cls).toContain('animate-pulse');
    expect(cls).toContain('rounded-md');
    expect(cls).toContain('bg-muted');
  });

  it('is hidden from assistive tech', () => {
    const w = mount(Skeleton);
    expect(w.attributes('aria-hidden')).toBe('true');
  });

  it('exposes a data-slot for slot-style scoped styles', () => {
    const w = mount(Skeleton);
    expect(w.attributes('data-slot')).toBe('skeleton');
  });

  it('merges caller class prop', () => {
    const w = mount(Skeleton, { props: { class: 'h-4 w-32' } });
    const cls = w.classes().join(' ');
    expect(cls).toContain('h-4');
    expect(cls).toContain('w-32');
    expect(cls).toContain('bg-muted');
  });
});
