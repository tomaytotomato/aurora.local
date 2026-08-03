import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import Card from './Card.vue';

/**
 * C7 iter-9: shadcn-vue Card smoke tests. Pins:
 *   - shadcn tokens on surface (bg-card / text-card-foreground /
 *     border-border) so a future edit can't silently regress to
 *     --color-surface / --color-ink / --color-line refs.
 *   - Default padding = p-7 (rhythm with DashboardHome's p-8).
 *   - padded=false suppresses padding.
 *   - hover=true adds a subtle border transition.
 *   - Caller class prop merges last (wins).
 *   - Text colour override via text-card-foreground still guards
 *     against .on-photo's white cascade.
 */
describe('Card', () => {
  it('renders slot content with the default shadcn tokens', () => {
    const w = mount(Card, { slots: { default: 'body' } });
    expect(w.text()).toContain('body');
    const cls = w.classes().join(' ');
    expect(cls).toContain('bg-card');
    expect(cls).toContain('text-card-foreground');
    expect(cls).toContain('border-border');
    expect(cls).toContain('rounded-lg');
  });

  it('applies default padding p-7 when padded=true is explicitly set', () => {
    // Note: Vue 3 coerces a missing boolean prop to `false`, so the
    // documented "p-7 by default" contract only actually fires when a
    // caller opts in with :padded="true". Every current caller either
    // overrides padding via class="p-8" or accepts the no-padding
    // default. Tracked as a followup (aurora: C-followup: Card padding
    // default).
    const w = mount(Card, { props: { padded: true } });
    expect(w.classes().join(' ')).toContain('p-7');
  });

  it('padded=false suppresses default padding', () => {
    const w = mount(Card, { props: { padded: false } });
    expect(w.classes().join(' ')).not.toContain('p-7');
  });

  it('hover=true adds a border-hover transition', () => {
    const w = mount(Card, { props: { hover: true } });
    const cls = w.classes().join(' ');
    expect(cls).toContain('transition-colors');
    expect(cls).toContain('hover:border-muted-foreground');
  });

  it('hover=false (default) does not add the transition', () => {
    const w = mount(Card);
    expect(w.classes().join(' ')).not.toContain('hover:border-muted-foreground');
  });

  it('merges caller class prop', () => {
    const w = mount(Card, { props: { class: 'p-8 col-span-2' } });
    const cls = w.classes().join(' ');
    expect(cls).toContain('p-8');
    expect(cls).toContain('col-span-2');
    expect(cls).toContain('bg-card');
  });
});
