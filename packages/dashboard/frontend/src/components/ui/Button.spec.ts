import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import Button from './Button.vue';
import { buttonVariants } from './buttonVariants';

/**
 * C3 iter-5: shadcn-vue Button smoke tests. Pins the variant → shadcn
 * token mapping so a future CVA edit can't silently regress a variant
 * onto a legacy `--color-*` reference. Aurora keeps the pre-shadcn
 * variant names (primary/secondary/ghost/link/danger/accent) for
 * caller compatibility; these tests document the class contract that
 * lives underneath.
 */
describe('Button', () => {
  it('renders slot content and defaults to primary + type=button', () => {
    const w = mount(Button, { slots: { default: 'Save' } });
    expect(w.text()).toContain('Save');
    expect(w.element.tagName).toBe('BUTTON');
    expect(w.attributes('type')).toBe('button');
    const cls = w.classes().join(' ');
    expect(cls).toContain('bg-primary');
    expect(cls).toContain('text-primary-foreground');
    expect(cls).toContain('h-10'); // size=md default
  });

  it('applies the secondary variant tokens', () => {
    const w = mount(Button, { props: { variant: 'secondary' }, slots: { default: 'x' } });
    const cls = w.classes().join(' ');
    expect(cls).toContain('bg-secondary');
    expect(cls).toContain('text-secondary-foreground');
    expect(cls).toContain('border-border');
  });

  it('applies the ghost variant tokens', () => {
    const w = mount(Button, { props: { variant: 'ghost' } });
    const cls = w.classes().join(' ');
    expect(cls).toContain('bg-transparent');
    expect(cls).toContain('text-muted-foreground');
    expect(cls).toContain('hover:bg-muted');
  });

  it('applies the link variant tokens', () => {
    const w = mount(Button, { props: { variant: 'link' } });
    const cls = w.classes().join(' ');
    expect(cls).toContain('text-foreground');
    expect(cls).toContain('hover:underline');
    expect(cls).toContain('p-0');
  });

  it('applies the danger variant tokens', () => {
    const w = mount(Button, { props: { variant: 'danger' } });
    const cls = w.classes().join(' ');
    expect(cls).toContain('text-destructive');
    expect(cls).toContain('hover:bg-destructive/10');
    expect(cls).toContain('border-border');
  });

  it('accent keeps the amber brand token (intentionally not shadcn accent)', () => {
    const w = mount(Button, { props: { variant: 'accent' } });
    const cls = w.classes().join(' ');
    // Aurora amber CTA — see main.css comment re shadcn `accent` unmapped.
    expect(cls).toContain('bg-[var(--color-accent)]');
    expect(cls).toContain('text-[var(--color-on-accent)]');
  });

  it('applies size classes', () => {
    expect(mount(Button, { props: { size: 'sm' } }).classes().join(' ')).toContain('h-8');
    expect(mount(Button, { props: { size: 'lg' } }).classes().join(' ')).toContain('h-11');
  });

  it('respects the type prop', () => {
    const w = mount(Button, { props: { type: 'submit' } });
    expect(w.attributes('type')).toBe('submit');
  });

  it('disabled sets the disabled attribute and dims via opacity utility', () => {
    const w = mount(Button, { props: { disabled: true } });
    expect(w.attributes('disabled')).toBeDefined();
    expect(w.classes().join(' ')).toContain('disabled:opacity-40');
  });

  it('loading renders a spinner, disables the button, and sets aria-busy', () => {
    const w = mount(Button, { props: { loading: true }, slots: { default: 'Saving' } });
    expect(w.attributes('disabled')).toBeDefined();
    expect(w.attributes('aria-busy')).toBe('true');
    expect(w.find('span[aria-hidden="true"]').exists()).toBe(true);
    expect(w.text()).toContain('Saving');
  });

  it('merges caller class prop with variant classes', () => {
    const w = mount(Button, { props: { class: 'w-full mt-4' } });
    const cls = w.classes().join(' ');
    expect(cls).toContain('w-full');
    expect(cls).toContain('mt-4');
    expect(cls).toContain('bg-primary');
  });

  it('exposes a focus-visible ring for keyboard users', () => {
    const w = mount(Button);
    const cls = w.classes().join(' ');
    expect(cls).toContain('focus-visible:ring-2');
    expect(cls).toContain('focus-visible:ring-ring');
  });

  it('buttonVariants CVA declares all Aurora variants', () => {
    // Belt-and-braces: catches a refactor that drops a variant while a
    // string-based check still passes because the base class list
    // happens to include the substring.
    expect(buttonVariants({ variant: 'primary' })).toMatch(/bg-primary/);
    expect(buttonVariants({ variant: 'secondary' })).toMatch(/bg-secondary/);
    expect(buttonVariants({ variant: 'ghost' })).toMatch(/text-muted-foreground/);
    expect(buttonVariants({ variant: 'link' })).toMatch(/hover:underline/);
    expect(buttonVariants({ variant: 'danger' })).toMatch(/text-destructive/);
    expect(buttonVariants({ variant: 'accent' })).toMatch(/--color-accent/);
  });
});
