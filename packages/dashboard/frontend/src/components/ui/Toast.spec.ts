import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import Toast from './Toast.vue';
import Toaster from './Toaster.vue';
import { toast, dismiss, dismissAll, useToastQueue } from '@/composables/useToast';

/**
 * C10 iter-18: shadcn-vue Toast + Toaster + useToast composable.
 *
 * Coverage split:
 *   - Toast.vue — variant tokens, aria-live, title/description slots,
 *     action button, dismiss button emits.
 *   - useToast — queue add/remove, dismiss(id), dismissAll, auto-dismiss
 *     via setTimeout with fake timers.
 *   - Toaster — renders one <Toast> per queue entry, dismiss button
 *     removes the entry.
 */

describe('Toast (presentation)', () => {
  it('escalates destructive toasts to role=alert + aria-live=assertive', () => {
    const w = mount(Toast, { props: { description: 'Couldn\'t remove user', variant: 'destructive' } });
    expect(w.attributes('role')).toBe('alert');
    expect(w.attributes('aria-live')).toBe('assertive');
  });

  it('renders description + aria-live=polite', () => {
    const w = mount(Toast, { props: { description: 'hello' } });
    expect(w.text()).toContain('hello');
    expect(w.attributes('role')).toBe('status');
    expect(w.attributes('aria-live')).toBe('polite');
    expect(w.attributes('data-slot')).toBe('toast');
  });

  it('default variant → bg-card + border-border', () => {
    const w = mount(Toast, { props: { description: 'x' } });
    const cls = w.classes().join(' ');
    expect(cls).toContain('bg-card');
    expect(cls).toContain('border-border');
    expect(w.attributes('data-variant')).toBe('default');
  });

  it('success / warning / destructive variants map to shadcn status tokens', () => {
    // Toast is always teleported straight to <body> as a floating overlay
    // (never inside a Card), so its tint is layered as a background-image
    // gradient on top of an opaque bg-card base rather than as a plain
    // bg-<tone>/10 background-color — that keeps the toast fully opaque
    // over the app-wide aurora photo instead of letting it show through.
    const s = mount(Toast, { props: { description: 'x', variant: 'success' } });
    expect(s.classes().join(' ')).toContain('bg-card');
    expect(s.classes().join(' ')).toContain('from-success/10');
    expect(s.classes().join(' ')).toContain('text-success');

    const w = mount(Toast, { props: { description: 'x', variant: 'warning' } });
    expect(w.classes().join(' ')).toContain('bg-card');
    expect(w.classes().join(' ')).toContain('from-warning/10');

    const d = mount(Toast, { props: { description: 'x', variant: 'destructive' } });
    expect(d.classes().join(' ')).toContain('bg-card');
    expect(d.classes().join(' ')).toContain('from-destructive/10');
    expect(d.classes().join(' ')).toContain('text-destructive');
  });

  it('renders title above description when supplied', () => {
    const w = mount(Toast, { props: { title: 'Saved', description: 'the file' } });
    expect(w.text()).toContain('Saved');
    expect(w.text()).toContain('the file');
  });

  it('dismiss button emits dismiss', async () => {
    const w = mount(Toast, { props: { description: 'x' } });
    await w.get('[data-slot="toast-dismiss"]').trigger('click');
    expect(w.emitted('dismiss')).toBeTruthy();
  });

  it('action button appears only with actionLabel and emits action', async () => {
    const w = mount(Toast, { props: { description: 'x' } });
    expect(w.find('[data-slot="toast-action"]').exists()).toBe(false);

    const w2 = mount(Toast, { props: { description: 'x', actionLabel: 'Undo' } });
    const btn = w2.get('[data-slot="toast-action"]');
    expect(btn.text()).toBe('Undo');
    await btn.trigger('click');
    expect(w2.emitted('action')).toBeTruthy();
  });
});

describe('useToast (queue)', () => {
  beforeEach(() => {
    dismissAll();
    vi.useFakeTimers();
  });
  afterEach(() => {
    dismissAll();
    vi.useRealTimers();
  });

  it('adds a toast to the queue and returns its id', () => {
    const id = toast({ description: 'hi' });
    const q = useToastQueue();
    expect(q.queue).toHaveLength(1);
    expect(q.queue[0].id).toBe(id);
    expect(q.queue[0].description).toBe('hi');
    expect(q.queue[0].variant).toBe('default');
    expect(q.queue[0].duration).toBe(5000);
  });

  it('respects variant + duration + title options', () => {
    toast({ description: 'boom', title: 'Uh oh', variant: 'destructive', duration: 8000 });
    const q = useToastQueue();
    expect(q.queue[0].title).toBe('Uh oh');
    expect(q.queue[0].variant).toBe('destructive');
    expect(q.queue[0].duration).toBe(8000);
  });

  it('auto-dismisses after the duration elapses', () => {
    const id = toast({ description: 'go', duration: 2000 });
    const q = useToastQueue();
    expect(q.queue).toHaveLength(1);

    vi.advanceTimersByTime(1999);
    expect(q.queue).toHaveLength(1);

    vi.advanceTimersByTime(1);
    expect(q.queue).toHaveLength(0);
    // Sanity — dismiss(id) after auto-dismiss is a no-op.
    dismiss(id);
    expect(q.queue).toHaveLength(0);
  });

  it('duration <= 0 disables auto-dismiss', () => {
    toast({ description: 'sticky', duration: -1 });
    const q = useToastQueue();
    vi.advanceTimersByTime(600000);
    expect(q.queue).toHaveLength(1);
  });

  it('dismiss(id) removes a specific toast + clears its timer', () => {
    const a = toast({ description: 'A' });
    const b = toast({ description: 'B' });
    const q = useToastQueue();
    expect(q.queue).toHaveLength(2);

    dismiss(a);
    expect(q.queue).toHaveLength(1);
    expect(q.queue[0].id).toBe(b);
  });

  it('dismissAll empties the queue', () => {
    toast({ description: 'A' });
    toast({ description: 'B' });
    toast({ description: 'C' });
    const q = useToastQueue();
    expect(q.queue).toHaveLength(3);
    dismissAll();
    expect(q.queue).toHaveLength(0);
  });
});

describe('Toaster (container)', () => {
  beforeEach(() => {
    dismissAll();
  });
  afterEach(() => {
    dismissAll();
  });

  it('renders one Toast per queue entry via Teleport', async () => {
    const w = mount(Toaster, { attachTo: document.body });
    toast({ description: 'first', duration: -1 });
    toast({ description: 'second', variant: 'success', duration: -1 });
    await flushPromises();
    const toasts = document.querySelectorAll('[data-slot="toast"]');
    expect(toasts).toHaveLength(2);
    expect(toasts[0].textContent).toContain('first');
    expect(toasts[1].textContent).toContain('second');
    w.unmount();
  });

  it('clicking the toast dismiss button removes it from the queue', async () => {
    const w = mount(Toaster, { attachTo: document.body });
    toast({ description: 'kill me', duration: -1 });
    await flushPromises();
    const q = useToastQueue();
    expect(q.queue).toHaveLength(1);

    (document.querySelector('[data-slot="toast-dismiss"]') as HTMLElement).click();
    await flushPromises();
    expect(q.queue).toHaveLength(0);
    w.unmount();
  });
});
