import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import Badge from './Badge.vue';
import { badgeVariants } from './badgeVariants';

/**
 * C4 iter-6: shadcn-vue Badge smoke tests. Pins tone → shadcn token
 * mapping so a future CVA edit can't silently regress a status pill
 * back onto legacy --color-*-bg / --color-*-fg references. Aurora
 * keeps `tone` (ok/warn/err/info/neutral) as the public API; six
 * callers depend on those names.
 */
describe('Badge', () => {
  it('renders slot content with the neutral tone by default', () => {
    const w = mount(Badge, { slots: { default: 'idle' } });
    expect(w.text()).toContain('idle');
    expect(w.element.tagName).toBe('SPAN');
    expect(w.attributes('role')).toBe('status');
    const cls = w.classes().join(' ');
    expect(cls).toContain('bg-muted');
    expect(cls).toContain('text-muted-foreground');
  });

  it('applies ok tone tokens', () => {
    const w = mount(Badge, { props: { tone: 'ok' }, slots: { default: 'running' } });
    const cls = w.classes().join(' ');
    expect(cls).toContain('bg-success/12');
    expect(cls).toContain('text-success');
  });

  it('applies warn tone tokens', () => {
    const w = mount(Badge, { props: { tone: 'warn' } });
    const cls = w.classes().join(' ');
    expect(cls).toContain('bg-warning/12');
    expect(cls).toContain('text-warning');
  });

  it('applies err tone tokens (mapped to shadcn destructive)', () => {
    const w = mount(Badge, { props: { tone: 'err' } });
    const cls = w.classes().join(' ');
    expect(cls).toContain('bg-destructive/12');
    expect(cls).toContain('text-destructive');
  });

  it('applies info tone tokens', () => {
    const w = mount(Badge, { props: { tone: 'info' } });
    const cls = w.classes().join(' ');
    expect(cls).toContain('bg-info/12');
    expect(cls).toContain('text-info');
  });

  it('renders a currentColor dot for coloured tones', () => {
    const w = mount(Badge, { props: { tone: 'ok' } });
    const dot = w.find('span[aria-hidden="true"]');
    expect(dot.exists()).toBe(true);
    expect(dot.classes().join(' ')).toContain('bg-current');
  });

  it('does NOT render the dot for the neutral tone', () => {
    const w = mount(Badge, { props: { tone: 'neutral' } });
    expect(w.find('span[aria-hidden="true"]').exists()).toBe(false);
  });

  it('merges caller class prop with tone classes', () => {
    const w = mount(Badge, { props: { tone: 'ok', class: 'ml-2 align-middle' } });
    const cls = w.classes().join(' ');
    expect(cls).toContain('ml-2');
    expect(cls).toContain('align-middle');
    expect(cls).toContain('bg-success/12');
  });

  it('badgeVariants CVA declares all Aurora tones', () => {
    // Belt-and-braces: catches a refactor that drops a tone even when a
    // string-based check still passes because the base class list
    // happens to include the substring.
    expect(badgeVariants({ tone: 'ok' })).toMatch(/bg-success\/12/);
    expect(badgeVariants({ tone: 'warn' })).toMatch(/bg-warning\/12/);
    expect(badgeVariants({ tone: 'err' })).toMatch(/bg-destructive\/12/);
    expect(badgeVariants({ tone: 'info' })).toMatch(/bg-info\/12/);
    expect(badgeVariants({ tone: 'neutral' })).toMatch(/bg-muted/);
  });
});
