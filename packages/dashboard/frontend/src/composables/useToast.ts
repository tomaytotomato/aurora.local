import { reactive, readonly } from 'vue';

// Aurora Toast composable (C10 iter-18).
//
// Sonner-inspired but self-contained — no external dep. Module-scoped
// reactive queue so `toast()` from any composable / axios interceptor
// / view can enqueue without prop-drilling. <Toaster /> mounted at App
// level renders the queue via Teleport.
//
// Design goals:
// - Fire-and-forget API: `toast({ description: 'Saved.' })`.
// - Semantic variants (default / success / warning / destructive) that
//   map onto Alert's shadcn tokens for visual consistency.
// - Auto-dismiss with per-toast `duration` (default 5000 ms; -1 disables).
// - Manual dismiss via the returned `id` or the toast's built-in X.
// - Focus-safe: toasts don't steal focus (aria-live=polite handles
//   announcement; keyboard users are not yanked mid-form).

export type ToastVariant = 'default' | 'success' | 'warning' | 'destructive';

export interface ToastOptions {
  title?: string;
  description: string;
  variant?: ToastVariant;
  duration?: number; // ms — default 5000; pass -1 to disable auto-dismiss
  actionLabel?: string;
  onAction?: () => void;
}

export interface ActiveToast extends Required<Omit<ToastOptions, 'onAction' | 'actionLabel'>> {
  id: number;
  actionLabel?: string;
  onAction?: () => void;
}

interface ToastStore {
  queue: ActiveToast[];
  seq: number;
}

const store = reactive<ToastStore>({ queue: [], seq: 0 });
// Track scheduled timeouts so dismiss() can cancel them and tests can
// clean up without leaving orphaned timers behind.
const timers = new Map<number, number>();

export function toast(opts: ToastOptions): number {
  store.seq += 1;
  const id = store.seq;
  const entry: ActiveToast = {
    id,
    title: opts.title ?? '',
    description: opts.description,
    variant: opts.variant ?? 'default',
    duration: opts.duration ?? 5000,
    actionLabel: opts.actionLabel,
    onAction: opts.onAction,
  };
  store.queue.push(entry);

  if (entry.duration > 0 && typeof window !== 'undefined') {
    const t = window.setTimeout(() => dismiss(id), entry.duration);
    timers.set(id, t);
  }
  return id;
}

export function dismiss(id: number): void {
  const idx = store.queue.findIndex((t) => t.id === id);
  if (idx !== -1) store.queue.splice(idx, 1);
  const timer = timers.get(id);
  if (timer !== undefined && typeof window !== 'undefined') {
    window.clearTimeout(timer);
    timers.delete(id);
  }
}

export function dismissAll(): void {
  // Snapshot ids first — dismiss() mutates the queue in place, so a
  // forward `for (t of store.queue)` skips every other entry.
  const ids = store.queue.map((t) => t.id);
  for (const id of ids) dismiss(id);
}

export function useToastQueue() {
  // Consumers (Toaster.vue, tests) get a read-only view. Mutation must
  // go through toast() / dismiss() so timers stay in sync with the queue.
  return readonly(store);
}
