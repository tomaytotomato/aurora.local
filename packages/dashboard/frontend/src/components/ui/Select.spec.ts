import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import Select from './Select.vue';

/**
 * C10 iter-16: shadcn-vue Select smoke tests.
 *
 * The primitive wraps a native <select> so keyboard nav / mobile
 * picker / screen-reader semantics come for free. Tests focus on:
 *   - shadcn visual tokens (bg-card / border-input / focus ring)
 *   - options render with correct value + label + disabled attrs
 *   - update:modelValue fires on change and preserves numeric type
 *   - the chevron indicator background survives future edits
 *   - class merge lets callers override height/width
 */
describe('Select', () => {
  const stringOpts = [
    { value: 'a', label: 'Alpha' },
    { value: 'b', label: 'Bravo' },
    { value: 'c', label: 'Charlie', disabled: true },
  ] as const;

  const numericOpts = [
    { value: 100, label: '100' },
    { value: 500, label: '500' },
    { value: 1000, label: '1000' },
  ] as const;

  it('renders a native <select> styled with shadcn tokens', () => {
    const w = mount(Select, { props: { modelValue: 'a', options: stringOpts } });
    // Root is a positioned wrapper; the actual <select> lives inside.
    expect(w.element.tagName).toBe('DIV');
    expect(w.attributes('data-slot')).toBe('select-wrapper');
    const select = w.get('select');
    expect(select.attributes('data-slot')).toBe('select-trigger');
    const cls = select.classes().join(' ');
    expect(cls).toContain('bg-card');
    expect(cls).toContain('text-foreground');
    expect(cls).toContain('border-input');
    expect(cls).toContain('focus-visible:ring-ring');
    expect(cls).toContain('appearance-none');
  });

  it('renders one <option> per entry with value + label + disabled', () => {
    const w = mount(Select, { props: { modelValue: 'a', options: stringOpts } });
    const opts = w.findAll('option');
    expect(opts).toHaveLength(3);
    expect(opts[0].attributes('value')).toBe('a');
    expect(opts[0].text()).toBe('Alpha');
    expect(opts[2].attributes('disabled')).toBeDefined();
  });

  it('emits update:modelValue with string coercion for string selects', async () => {
    const w = mount(Select, { props: { modelValue: 'a', options: stringOpts } });
    await w.get('select').setValue('b');
    const emits = w.emitted('update:modelValue');
    expect(emits).toBeTruthy();
    expect(emits![0]).toEqual(['b']);
    expect(typeof emits![0][0]).toBe('string');
  });

  it('emits update:modelValue with NUMBER coercion when modelValue is numeric', async () => {
    const w = mount(Select, { props: { modelValue: 100, options: numericOpts } });
    await w.get('select').setValue(500);
    const emits = w.emitted('update:modelValue');
    expect(emits).toBeTruthy();
    expect(emits![0]).toEqual([500]);
    // Critical: must be number, not string. The whole point of the
    // typeof-modelValue coercion in Select.vue.
    expect(typeof emits![0][0]).toBe('number');
  });

  it('respects disabled and applies muted styling', () => {
    const w = mount(Select, {
      props: { modelValue: 'a', options: stringOpts, disabled: true },
    });
    const select = w.get('select');
    expect(select.attributes('disabled')).toBeDefined();
    const cls = select.classes().join(' ');
    expect(cls).toContain('disabled:bg-muted');
    expect(cls).toContain('disabled:text-muted-foreground');
  });

  it('passes id + name + aria-label through', () => {
    const w = mount(Select, {
      props: {
        modelValue: 'a',
        options: stringOpts,
        id: 'sev',
        name: 'severity',
        ariaLabel: 'Severity picker',
      },
    });
    const select = w.get('select');
    expect(select.attributes('id')).toBe('sev');
    expect(select.attributes('name')).toBe('severity');
    expect(select.attributes('aria-label')).toBe('Severity picker');
  });

  it('overlays a chevron SVG so vendor pickers stay hidden', () => {
    const w = mount(Select, { props: { modelValue: 'a', options: stringOpts } });
    const chev = w.find('[data-slot="select-chevron"]');
    expect(chev.exists()).toBe(true);
    expect(chev.element.tagName.toLowerCase()).toBe('svg');
    expect(chev.attributes('aria-hidden')).toBe('true');
    const cls = chev.classes().join(' ');
    expect(cls).toContain('pointer-events-none');
    expect(cls).toContain('text-muted-foreground');
  });

  it('merges caller class prop (height + width overrides)', () => {
    const w = mount(Select, {
      props: { modelValue: 'a', options: stringOpts, class: 'h-8 w-24' },
    });
    const cls = w.get('select').classes().join(' ');
    expect(cls).toContain('h-8');
    expect(cls).toContain('w-24');
    expect(cls).toContain('bg-card');
  });
});
