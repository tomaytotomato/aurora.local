import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import DropdownMenu from './DropdownMenu.vue';
import DropdownMenuItem from './DropdownMenuItem.vue';
import DropdownMenuSeparator from './DropdownMenuSeparator.vue';

/**
 * C10 iter-20: shadcn-vue DropdownMenu + Item + Separator smoke tests.
 *
 * Covers:
 *   - Trigger toggles content visibility.
 *   - aria-haspopup + aria-expanded track state.
 *   - Click-outside closes the menu.
 *   - ESC closes the menu.
 *   - Alignment prop chooses left-0 vs right-0.
 *   - MenuItem emits select on click, respects disabled, applies
 *     destructive tokens.
 *   - Separator is role=separator.
 *
 * Arrow-key nav is NOT exercised here (needs a real focus model with
 * multiple items rendered); a follow-up e2e can cover the a11y
 * happy path.
 */

const triggerSlot = '<button data-test="trigger">Open</button>';
const itemsSlot = `
  <button data-menu-item data-slot="dropdown-item" data-test="item-a">A</button>
  <button data-menu-item data-slot="dropdown-item" data-test="item-b">B</button>
`;

function mountMenu(props: Partial<{ align: 'left' | 'right' }> = {}) {
  return mount(DropdownMenu, {
    props,
    slots: {
      trigger: triggerSlot,
      default: itemsSlot,
    },
    attachTo: document.body,
  });
}

describe('DropdownMenu', () => {
  afterEach(() => {
    // Clean up any document-level listeners a still-open menu might hold.
    // Each test unmounts, but keeping this belt-and-braces guards against
    // an assertion failure that skips the unmount.
  });

  it('renders trigger and hides content initially', () => {
    const w = mountMenu();
    expect(w.find('[data-test="trigger"]').exists()).toBe(true);
    expect(w.find('[role="menu"]').exists()).toBe(false);
    const trig = w.get('[data-slot="dropdown-trigger"]');
    expect(trig.attributes('aria-haspopup')).toBe('menu');
    expect(trig.attributes('aria-expanded')).toBe('false');
    w.unmount();
  });

  it('trigger click opens the menu and flips aria-expanded', async () => {
    const w = mountMenu();
    await w.get('[data-slot="dropdown-trigger"]').trigger('click');
    expect(w.find('[role="menu"]').exists()).toBe(true);
    expect(w.get('[data-slot="dropdown-trigger"]').attributes('aria-expanded')).toBe('true');
    w.unmount();
  });

  it('trigger click again closes the menu', async () => {
    const w = mountMenu();
    const trig = w.get('[data-slot="dropdown-trigger"]');
    await trig.trigger('click');
    await trig.trigger('click');
    expect(w.find('[role="menu"]').exists()).toBe(false);
    w.unmount();
  });

  it('click outside closes the menu', async () => {
    const w = mountMenu();
    await w.get('[data-slot="dropdown-trigger"]').trigger('click');
    expect(w.find('[role="menu"]').exists()).toBe(true);

    // Dispatch a click on document.body (outside the root).
    document.body.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await flushPromises();
    expect(w.find('[role="menu"]').exists()).toBe(false);
    w.unmount();
  });

  it('ESC closes the menu', async () => {
    const w = mountMenu();
    await w.get('[data-slot="dropdown-trigger"]').trigger('click');
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await flushPromises();
    expect(w.find('[role="menu"]').exists()).toBe(false);
    w.unmount();
  });

  it('align="left" applies left-0 to content, default is right-0', async () => {
    const r = mountMenu();
    await r.get('[data-slot="dropdown-trigger"]').trigger('click');
    expect(r.get('[role="menu"]').classes().join(' ')).toContain('right-0');
    r.unmount();

    const l = mountMenu({ align: 'left' });
    await l.get('[data-slot="dropdown-trigger"]').trigger('click');
    expect(l.get('[role="menu"]').classes().join(' ')).toContain('left-0');
    l.unmount();
  });

  it('menu content is styled with shadcn popover tokens', async () => {
    const w = mountMenu();
    await w.get('[data-slot="dropdown-trigger"]').trigger('click');
    const cls = w.get('[role="menu"]').classes().join(' ');
    expect(cls).toContain('bg-popover');
    expect(cls).toContain('text-popover-foreground');
    expect(cls).toContain('border-border');
    expect(cls).toContain('shadow-lg');
    w.unmount();
  });
});

describe('DropdownMenuItem', () => {
  it('renders a role=menuitem button with shadcn tokens + data-menu-item', () => {
    const w = mount(DropdownMenuItem, { slots: { default: 'Sign out' } });
    expect(w.element.tagName).toBe('BUTTON');
    expect(w.attributes('role')).toBe('menuitem');
    expect(w.attributes('data-menu-item')).toBe('');
    expect(w.attributes('data-slot')).toBe('dropdown-item');
    expect(w.text()).toBe('Sign out');
    const cls = w.classes().join(' ');
    expect(cls).toContain('hover:bg-muted');
    expect(cls).toContain('focus:bg-muted');
  });

  it('emits select on click', async () => {
    const w = mount(DropdownMenuItem, { slots: { default: 'x' } });
    await w.trigger('click');
    expect(w.emitted('select')).toBeTruthy();
  });

  it('disabled blocks the select emit and applies opacity', async () => {
    const w = mount(DropdownMenuItem, { props: { disabled: true }, slots: { default: 'x' } });
    expect(w.attributes('disabled')).toBeDefined();
    await w.trigger('click');
    expect(w.emitted('select')).toBeUndefined();
    expect(w.classes().join(' ')).toContain('disabled:opacity-40');
  });

  it('destructive variant applies destructive tokens', () => {
    const w = mount(DropdownMenuItem, {
      props: { destructive: true },
      slots: { default: 'Delete' },
    });
    const cls = w.classes().join(' ');
    expect(cls).toContain('text-destructive');
    expect(cls).toContain('hover:bg-destructive/10');
  });
});

describe('DropdownMenuSeparator', () => {
  it('renders a role=separator hair-line', () => {
    const w = mount(DropdownMenuSeparator);
    expect(w.attributes('role')).toBe('separator');
    expect(w.attributes('data-slot')).toBe('dropdown-separator');
    const cls = w.classes().join(' ');
    expect(cls).toContain('h-px');
    expect(cls).toContain('bg-border');
  });
});
