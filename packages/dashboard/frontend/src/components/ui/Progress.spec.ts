import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import Progress from './Progress.vue';

/**
 * C8 iter-10: shadcn-vue Progress smoke tests. Pins:
 *   - track = bg-secondary, fill = bg-primary (shadcn tokens).
 *   - ARIA: role=progressbar with valuemin/max/now.
 *   - Fill width clamps to [0, 100] on the style.
 *   - Caller class prop merges into the outer track.
 */
describe('Progress', () => {
  it('renders track with bg-secondary and shadcn tokens', () => {
    const w = mount(Progress, { props: { value: 40 } });
    const cls = w.classes().join(' ');
    expect(cls).toContain('bg-secondary');
    expect(cls).toContain('rounded-full');
    expect(cls).toContain('h-1');
  });

  it('renders fill with bg-primary and width = value%', () => {
    const w = mount(Progress, { props: { value: 40 } });
    const fill = w.find('[data-testid="progress-fill"]');
    expect(fill.exists()).toBe(true);
    expect(fill.classes().join(' ')).toContain('bg-primary');
    expect(fill.attributes('style')).toContain('width: 40%');
  });

  it('exposes ARIA progressbar semantics', () => {
    const w = mount(Progress, { props: { value: 75 } });
    expect(w.attributes('role')).toBe('progressbar');
    expect(w.attributes('aria-valuemin')).toBe('0');
    expect(w.attributes('aria-valuemax')).toBe('100');
    expect(w.attributes('aria-valuenow')).toBe('75');
  });

  it('clamps negative values to 0', () => {
    const w = mount(Progress, { props: { value: -20 } });
    expect(w.find('[data-testid="progress-fill"]').attributes('style')).toContain('width: 0%');
    expect(w.attributes('aria-valuenow')).toBe('0');
  });

  it('clamps values above 100', () => {
    const w = mount(Progress, { props: { value: 250 } });
    expect(w.find('[data-testid="progress-fill"]').attributes('style')).toContain('width: 100%');
    expect(w.attributes('aria-valuenow')).toBe('100');
  });

  it('merges caller class prop into the outer track', () => {
    const w = mount(Progress, { props: { value: 10, class: 'rail-progress mt-2' } });
    const cls = w.classes().join(' ');
    expect(cls).toContain('rail-progress');
    expect(cls).toContain('mt-2');
    expect(cls).toContain('bg-secondary');
  });
});
