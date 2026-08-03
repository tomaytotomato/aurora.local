import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import Input from './Input.vue';
import Label from './Label.vue';
import Checkbox from './Checkbox.vue';

/**
 * C5 iter-7: shadcn-vue form primitive smoke tests. Pins the
 * migrated token contracts so a future refactor can't silently
 * regress Input/Label/Checkbox back onto legacy --color-* refs.
 *
 * These primitives have no CVA variants — asserting on the flat
 * class list is enough. If a variant surface ever appears (e.g.
 * Input `size`), extract to a *Variants.ts sidecar first.
 */

describe('Input', () => {
  it('renders with default type=text and shadcn tokens', () => {
    const w = mount(Input, { props: { modelValue: '' } });
    expect(w.element.tagName).toBe('INPUT');
    expect(w.attributes('type')).toBe('text');
    const cls = w.classes().join(' ');
    expect(cls).toContain('bg-background');
    expect(cls).toContain('border-input');
    expect(cls).toContain('placeholder:text-muted-foreground');
    expect(cls).toContain('focus-visible:ring-ring');
  });

  it('emits update:modelValue on input', async () => {
    const w = mount(Input, { props: { modelValue: '' } });
    await w.find('input').setValue('hello');
    const emits = w.emitted('update:modelValue');
    expect(emits).toBeTruthy();
    expect(emits![0]).toEqual(['hello']);
  });

  it('applies invalid state — destructive border + ring + aria-invalid', () => {
    const w = mount(Input, { props: { modelValue: 'bad', invalid: true } });
    const cls = w.classes().join(' ');
    expect(cls).toContain('border-destructive');
    expect(cls).toContain('focus-visible:ring-destructive');
    expect(w.attributes('aria-invalid')).toBe('true');
  });

  it('respects disabled and applies muted styling', () => {
    const w = mount(Input, { props: { modelValue: '', disabled: true } });
    expect(w.attributes('disabled')).toBeDefined();
    const cls = w.classes().join(' ');
    expect(cls).toContain('disabled:bg-muted');
    expect(cls).toContain('disabled:text-muted-foreground');
  });

  it('merges caller class prop', () => {
    const w = mount(Input, { props: { modelValue: '', class: 'w-64 mt-2' } });
    const cls = w.classes().join(' ');
    expect(cls).toContain('w-64');
    expect(cls).toContain('mt-2');
    expect(cls).toContain('bg-background');
  });

  it('passes id / placeholder / autocomplete through', () => {
    const w = mount(Input, {
      props: {
        modelValue: '',
        id: 'email',
        placeholder: 'you@example.com',
        autocomplete: 'username',
        type: 'email',
      },
    });
    expect(w.attributes('id')).toBe('email');
    expect(w.attributes('placeholder')).toBe('you@example.com');
    expect(w.attributes('autocomplete')).toBe('username');
    expect(w.attributes('type')).toBe('email');
  });
});

describe('Label', () => {
  it('renders slot content on a label with shadcn tokens', () => {
    const w = mount(Label, { slots: { default: 'Email' } });
    expect(w.element.tagName).toBe('LABEL');
    expect(w.text()).toContain('Email');
    const cls = w.classes().join(' ');
    expect(cls).toContain('text-foreground');
    expect(cls).toContain('font-medium');
    expect(cls).toContain('peer-disabled:opacity-70');
  });

  it('passes for-attribute through', () => {
    const w = mount(Label, { props: { for: 'email' } });
    expect(w.attributes('for')).toBe('email');
  });

  it('renders the optional hint with muted-foreground', () => {
    const w = mount(Label, { props: { hint: 'optional' }, slots: { default: 'Alt name' } });
    expect(w.text()).toContain('optional');
    const hint = w.find('span');
    expect(hint.exists()).toBe(true);
    expect(hint.classes().join(' ')).toContain('text-muted-foreground');
  });

  it('merges caller class prop', () => {
    const w = mount(Label, { props: { class: 'sr-only' } });
    expect(w.classes().join(' ')).toContain('sr-only');
  });
});

describe('Checkbox', () => {
  it('renders as a role=checkbox button with aria-checked', () => {
    const w = mount(Checkbox, { props: { modelValue: false } });
    expect(w.element.tagName).toBe('BUTTON');
    expect(w.attributes('role')).toBe('checkbox');
    expect(w.attributes('aria-checked')).toBe('false');
  });

  it('unchecked → bg-background / border-input', () => {
    const w = mount(Checkbox, { props: { modelValue: false } });
    const cls = w.classes().join(' ');
    expect(cls).toContain('bg-background');
    expect(cls).toContain('border-input');
    expect(w.find('svg').exists()).toBe(false);
  });

  it('checked → bg-primary / text-primary-foreground / border-primary + tick svg', () => {
    const w = mount(Checkbox, { props: { modelValue: true } });
    const cls = w.classes().join(' ');
    expect(cls).toContain('bg-primary');
    expect(cls).toContain('text-primary-foreground');
    expect(cls).toContain('border-primary');
    expect(w.attributes('aria-checked')).toBe('true');
    expect(w.find('svg').exists()).toBe(true);
    expect(w.find('svg').attributes('aria-hidden')).toBe('true');
  });

  it('toggles on click', async () => {
    const w = mount(Checkbox, { props: { modelValue: false } });
    await w.trigger('click');
    const emits = w.emitted('update:modelValue');
    expect(emits).toBeTruthy();
    expect(emits![0]).toEqual([true]);
  });

  it('respects disabled', () => {
    const w = mount(Checkbox, { props: { modelValue: false, disabled: true } });
    expect(w.attributes('disabled')).toBeDefined();
    const cls = w.classes().join(' ');
    expect(cls).toContain('opacity-40');
    expect(cls).toContain('pointer-events-none');
  });

  it('exposes focus-visible ring', () => {
    const w = mount(Checkbox, { props: { modelValue: false } });
    const cls = w.classes().join(' ');
    expect(cls).toContain('focus-visible:ring-2');
    expect(cls).toContain('focus-visible:ring-ring');
  });

  it('merges caller class prop', () => {
    const w = mount(Checkbox, { props: { modelValue: false, class: 'mr-2' } });
    expect(w.classes().join(' ')).toContain('mr-2');
  });
});
