import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import Alert from './Alert.vue';
import AlertTitle from './AlertTitle.vue';
import AlertDescription from './AlertDescription.vue';
import { alertVariants } from './alertVariants';

/**
 * C1 iter-3: shadcn-vue Alert smoke tests. Pins the variant→class
 * mapping so future CVA refactors can't silently drop `warning`,
 * `info`, or `success` — those are our semantic extensions on top of
 * shadcn's default/destructive base and would otherwise be invisible
 * to a caller until a live screenshot lands.
 */
describe('Alert', () => {
  it('renders slot content with the default variant', () => {
    const w = mount(Alert, { slots: { default: 'plain text' } });
    expect(w.text()).toContain('plain text');
    expect(w.attributes('role')).toBe('alert');
    expect(w.classes().join(' ')).toContain('bg-background');
  });

  it('applies the destructive variant classes', () => {
    const w = mount(Alert, {
      props: { variant: 'destructive' },
      slots: { default: 'boom' },
    });
    const cls = w.classes().join(' ');
    expect(cls).toContain('bg-destructive/8');
    expect(cls).toContain('text-destructive');
    expect(cls).toContain('border-destructive/40');
  });

  it('applies the warning variant classes', () => {
    const w = mount(Alert, { props: { variant: 'warning' } });
    const cls = w.classes().join(' ');
    expect(cls).toContain('bg-warning/8');
    expect(cls).toContain('text-warning');
  });

  it('applies the info variant classes', () => {
    const w = mount(Alert, { props: { variant: 'info' } });
    expect(w.classes().join(' ')).toContain('text-info');
  });

  it('applies the success variant classes', () => {
    const w = mount(Alert, { props: { variant: 'success' } });
    expect(w.classes().join(' ')).toContain('text-success');
  });

  it('merges caller class prop with variant classes', () => {
    const w = mount(Alert, { props: { class: 'mt-6 mb-2' } });
    const cls = w.classes().join(' ');
    expect(cls).toContain('mt-6');
    expect(cls).toContain('mb-2');
    expect(cls).toContain('bg-background');
  });

  it('AlertTitle renders as an h5 with heading styling', () => {
    const w = mount(AlertTitle, { slots: { default: 'Something failed' } });
    expect(w.element.tagName).toBe('H5');
    expect(w.text()).toBe('Something failed');
    expect(w.classes().join(' ')).toContain('font-medium');
  });

  it('AlertDescription renders slot content', () => {
    const w = mount(AlertDescription, { slots: { default: 'details' } });
    expect(w.text()).toBe('details');
  });

  it('alertVariants CVA declares all extended variants', () => {
    // Belt-and-braces: catches the case where a refactor drops a variant
    // and the string-based `expect(...).toContain` above still passes
    // because the default classes happen to include the substring.
    expect(alertVariants({ variant: 'default' })).toMatch(/bg-background/);
    expect(alertVariants({ variant: 'destructive' })).toMatch(/text-destructive/);
    expect(alertVariants({ variant: 'warning' })).toMatch(/text-warning/);
    expect(alertVariants({ variant: 'info' })).toMatch(/text-info/);
    expect(alertVariants({ variant: 'success' })).toMatch(/text-success/);
  });
});
