import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import Dialog from './Dialog.vue';

/**
 * C10 iter-15: shadcn-vue Dialog smoke tests.
 *
 * The trickier interaction properties (focus trap, ESC close, backdrop
 * dismiss, body scroll lock) are unit-tested here so a future
 * "just a token tweak" edit can't silently break the trap. Named-slot
 * ARIA wiring is also asserted because a screen reader failure would
 * be invisible in a diff.
 *
 * Note: We use `attachTo: document.body` so document.activeElement +
 * Teleport target both work properly under jsdom.
 */

function makeMount(props: Partial<{ open: boolean; dismissable: boolean }> = {}) {
  return mount(Dialog, {
    props: { open: true, ...props },
    slots: {
      title: 'Password recovery',
      description: 'Recovery coming shortly…',
      default: '<input data-test="first-input" /><input data-test="second-input" />',
      footer: '<button data-test="dismiss">Got it</button>',
    },
    attachTo: document.body,
  });
}

describe('Dialog', () => {
  beforeEach(() => {
    document.body.style.overflow = '';
  });
  afterEach(() => {
    document.body.style.overflow = '';
  });

  it('teleports content to <body> as role=dialog with aria wiring', async () => {
    const w = makeMount();
    await flushPromises();

    const panel = document.querySelector('[role="dialog"]') as HTMLElement;
    expect(panel).toBeTruthy();
    expect(panel.getAttribute('aria-modal')).toBe('true');

    const titleId = panel.getAttribute('aria-labelledby');
    const descId = panel.getAttribute('aria-describedby');
    expect(titleId).toBeTruthy();
    expect(descId).toBeTruthy();
    expect(document.getElementById(titleId!)?.textContent).toBe('Password recovery');
    expect(document.getElementById(descId!)?.textContent).toContain('Recovery coming');

    w.unmount();
  });

  it('renders nothing when open=false', () => {
    const w = mount(Dialog, { props: { open: false }, attachTo: document.body });
    expect(document.querySelector('[role="dialog"]')).toBeFalsy();
    w.unmount();
  });

  it('locks body scroll while open and releases on close', async () => {
    const w = makeMount();
    await flushPromises();
    expect(document.body.style.overflow).toBe('hidden');

    await w.setProps({ open: false });
    await flushPromises();
    expect(document.body.style.overflow).toBe('');
    w.unmount();
  });

  it('focuses the first focusable inside the panel on open', async () => {
    const w = makeMount();
    await flushPromises();
    const first = document.querySelector('[data-test="first-input"]') as HTMLElement;
    expect(document.activeElement).toBe(first);
    w.unmount();
  });

  it('ESC closes the dialog and emits update:open + close', async () => {
    const w = makeMount();
    await flushPromises();

    // Dialog attaches a document-level keydown listener while open so
    // ESC works regardless of what has focus.
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await flushPromises();

    expect(w.emitted('update:open')?.[0]).toEqual([false]);
    expect(w.emitted('close')).toBeTruthy();
    w.unmount();
  });

  it('backdrop click closes when dismissable (default)', async () => {
    const w = makeMount();
    await flushPromises();
    const overlay = document.querySelector('[data-slot="dialog-overlay"]') as HTMLElement;
    // Native click bubbles — vue-test-utils attaches @click via addEventListener
    // so a raw dispatchEvent does fire the handler. We just need target ===
    // currentTarget so the overlay-vs-panel check passes.
    const ev = new MouseEvent('click', { bubbles: true, cancelable: true });
    overlay.dispatchEvent(ev);
    await flushPromises();
    expect(w.emitted('update:open')?.[0]).toEqual([false]);
    w.unmount();
  });

  it('backdrop click does NOT close when dismissable=false', async () => {
    const w = makeMount({ dismissable: false });
    await flushPromises();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await flushPromises();
    expect(w.emitted('update:open')).toBeUndefined();
    w.unmount();
  });

  it('Tab cycles from the last focusable back to the first', async () => {
    const w = makeMount();
    await flushPromises();

    const last = document.querySelector('[data-test="dismiss"]') as HTMLElement;
    last.focus();
    expect(document.activeElement).toBe(last);

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }));
    await flushPromises();

    const first = document.querySelector('[data-test="first-input"]') as HTMLElement;
    expect(document.activeElement).toBe(first);
    w.unmount();
  });

  it('Shift+Tab cycles from the first focusable back to the last', async () => {
    const w = makeMount();
    await flushPromises();

    const first = document.querySelector('[data-test="first-input"]') as HTMLElement;
    first.focus();

    document.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true }),
    );
    await flushPromises();

    const last = document.querySelector('[data-test="dismiss"]') as HTMLElement;
    expect(document.activeElement).toBe(last);
    w.unmount();
  });
});
