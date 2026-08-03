// iter-36 (v0.3 followup): pure-function helpers for the container-events
// ring buffer used by `useContainerEvents`. Extracted so vitest can pin
// dedupe + cap behaviour without mounting a full Vue component + faking
// EventSource.
//
// The full composable still owns EventSource lifecycle, poll fallback,
// and Vue reactivity. This module only handles the array-in / array-out
// arithmetic \u2014 no side effects, no imports of the composable itself
// so tests can import freely.

import type { ContainerEventItem } from '@/api/containers';

/**
 * Client-side cap. Matches DockerEventService.BUFFER_MAX so the client
 * can never grow beyond the honest server ceiling.
 */
export const MAX_EVENTS = 200;

/**
 * Append a single event to the buffer with tail-based dedupe. Returns a
 * new array (or the same reference when the input was a dup) so callers
 * can trivially detect whether the reactive ref needs a swap.
 *
 * <p>Dedupe key: (ts, container, action) \u2014 the only stable identity
 * docker gives us. Cheap because the check is against the tail only
 * (recent events are the ones that collide via SSE replay + poll
 * fallback races).
 */
export function pushDeduped(
  buffer: readonly ContainerEventItem[],
  next: ContainerEventItem,
  max: number = MAX_EVENTS,
): readonly ContainerEventItem[] {
  const tail = buffer[buffer.length - 1];
  if (
    tail !== undefined
    && tail.ts === next.ts
    && tail.container === next.container
    && tail.action === next.action
  ) {
    return buffer;
  }
  const out = buffer.length + 1 > max
    ? [...buffer.slice(buffer.length + 1 - max), next]
    : [...buffer, next];
  return out;
}

/**
 * Poll refresh path: the backend returns oldest-first. Trust that shape,
 * slice-tail to enforce the client cap, and return a new array so the
 * caller can swap the reactive ref in one write.
 */
export function replaceCapped(
  incoming: readonly ContainerEventItem[],
  max: number = MAX_EVENTS,
): readonly ContainerEventItem[] {
  if (incoming.length <= max) return incoming.slice();
  return incoming.slice(-max);
}

/**
 * Sliding-window failure counter used by the SSE fallback ladder.
 * Prunes anything outside `windowMs` on write so callers don't have to.
 */
export function pruneFailureWindow(
  timestamps: readonly number[],
  now: number,
  windowMs: number,
): number[] {
  const kept: number[] = [];
  for (const t of timestamps) {
    if (now - t <= windowMs) kept.push(t);
  }
  return kept;
}
