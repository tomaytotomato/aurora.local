import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import Tabs from './Tabs.vue';

/**
 * C6 iter-8: shadcn-vue Tabs smoke tests. Pins:
 *   - shadcn tokens on the tablist border, active/inactive triggers,
 *     and the underline indicator (would silently regress to
 *     --color-line / --color-ink otherwise).
 *   - ARIA tab pattern: role, aria-selected, roving tabindex (only
 *     the active trigger is tabbable, matching WAI-ARIA APG).
 *   - focus-visible ring for keyboard users.
 *   - Panel content rendered from the default slot.
 */
describe('Tabs', () => {
  const tabs = [
    { value: 'overview', label: 'Overview' },
    { value: 'config', label: 'Config' },
    { value: 'logs', label: 'Logs' },
  ] as const;

  it('renders a tablist with one button per tab', () => {
    const w = mount(Tabs, {
      props: { modelValue: 'overview', tabs },
      slots: { default: '<div data-test="panel">panel</div>' },
    });
    const list = w.find('[role="tablist"]');
    expect(list.exists()).toBe(true);
    expect(list.classes().join(' ')).toContain('border-border');
    const triggers = w.findAll('[role="tab"]');
    expect(triggers).toHaveLength(3);
    expect(triggers.map((t) => t.text())).toEqual(['Overview', 'Config', 'Logs']);
  });

  it('marks the active trigger with aria-selected + tabindex=0 + text-foreground', () => {
    const w = mount(Tabs, { props: { modelValue: 'config', tabs } });
    const triggers = w.findAll('[role="tab"]');
    const [overview, config, logs] = triggers;

    expect(config.attributes('aria-selected')).toBe('true');
    expect(config.attributes('tabindex')).toBe('0');
    expect(config.classes().join(' ')).toContain('text-foreground');

    expect(overview.attributes('aria-selected')).toBe('false');
    expect(overview.attributes('tabindex')).toBe('-1');
    expect(overview.classes().join(' ')).toContain('text-muted-foreground');

    expect(logs.attributes('tabindex')).toBe('-1');
  });

  it('renders the underline indicator only under the active trigger', () => {
    const w = mount(Tabs, { props: { modelValue: 'overview', tabs } });
    const triggers = w.findAll('[role="tab"]');
    const activeIndicator = triggers[0].find('span[aria-hidden="true"]');
    expect(activeIndicator.exists()).toBe(true);
    expect(activeIndicator.classes().join(' ')).toContain('bg-foreground');
    // Inactive triggers should NOT render the indicator span.
    expect(triggers[1].find('span[aria-hidden="true"]').exists()).toBe(false);
    expect(triggers[2].find('span[aria-hidden="true"]').exists()).toBe(false);
  });

  it('emits update:modelValue on click', async () => {
    const w = mount(Tabs, { props: { modelValue: 'overview', tabs } });
    await w.findAll('[role="tab"]')[1].trigger('click');
    const emits = w.emitted('update:modelValue');
    expect(emits).toBeTruthy();
    expect(emits![0]).toEqual(['config']);
  });

  it('triggers expose a focus-visible ring', () => {
    const w = mount(Tabs, { props: { modelValue: 'overview', tabs } });
    const cls = w.findAll('[role="tab"]')[0].classes().join(' ');
    expect(cls).toContain('focus-visible:ring-2');
    expect(cls).toContain('focus-visible:ring-ring');
  });

  it('renders default slot content beneath the tablist', () => {
    const w = mount(Tabs, {
      props: { modelValue: 'overview', tabs },
      slots: { default: '<div data-test="panel">panel-body</div>' },
    });
    expect(w.find('[data-test="panel"]').text()).toBe('panel-body');
  });

  it('merges caller class prop into the tablist', () => {
    const w = mount(Tabs, { props: { modelValue: 'overview', tabs, class: 'mb-6' } });
    const cls = w.find('[role="tablist"]').classes().join(' ');
    expect(cls).toContain('mb-6');
    expect(cls).toContain('border-border');
  });

  it('renders an optional hint after the label', () => {
    const withHints = [
      { value: 'all', label: 'All', hint: '16' },
      { value: 'enabled', label: 'Enabled', hint: '6' },
    ] as const;
    const w = mount(Tabs, { props: { modelValue: 'all', tabs: withHints } });
    const first = w.findAll('[role="tab"]')[0];
    expect(first.find('small').exists()).toBe(true);
    expect(first.find('small').text()).toBe('16');
  });

  it('applies the compact padding under size="sm"', () => {
    const w = mount(Tabs, { props: { modelValue: 'overview', tabs, size: 'sm' } });
    const cls = w.findAll('[role="tab"]')[0].classes().join(' ');
    expect(cls).toContain('px-3');
    expect(cls).toContain('text-xs');
  });

  it('moves selection with ArrowRight/ArrowLeft/Home/End', async () => {
    const w = mount(Tabs, { props: { modelValue: 'config', tabs } });
    const list = w.find('[role="tablist"]');

    await list.trigger('keydown', { key: 'ArrowRight' });
    expect(w.emitted('update:modelValue')!.at(-1)).toEqual(['logs']);

    await list.trigger('keydown', { key: 'ArrowLeft' });
    expect(w.emitted('update:modelValue')!.at(-1)).toEqual(['overview']);

    await list.trigger('keydown', { key: 'End' });
    expect(w.emitted('update:modelValue')!.at(-1)).toEqual(['logs']);

    await list.trigger('keydown', { key: 'Home' });
    expect(w.emitted('update:modelValue')!.at(-1)).toEqual(['overview']);
  });

  it('wraps around at the ends', async () => {
    const w = mount(Tabs, { props: { modelValue: 'logs', tabs } });
    await w.find('[role="tablist"]').trigger('keydown', { key: 'ArrowRight' });
    expect(w.emitted('update:modelValue')!.at(-1)).toEqual(['overview']);
  });
});
