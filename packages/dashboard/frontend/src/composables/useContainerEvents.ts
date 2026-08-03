import { onMounted, onScopeDispose, ref, type Ref } from 'vue';
import {
  ContainersApi,
  openContainerEventsStream,
  type ContainerEventItem,
} from '@/api/containers';

/**
 * B1 (v0.3, iter-9): live feed of docker container-lifecycle events for
 * the DashboardHome "Recent changes" card. Subscribes to
 * {@code GET /api/containers/events/stream} (Server-Sent Events) and
 * falls back to 5s polling on {@code /api/containers/events} after 3
 * failures inside a 30s window. Same failure ladder as
 * {@link useServiceStatusStream} (TD1/A5 precedent) — kept intentionally
 * symmetrical so a future proxy issue diagnoses the same way for both
 * streams.
 *
 * <p>Contract for callers:
 * <ul>
 *   <li>{@code events} — rolling list of {@link ContainerEventItem}s,
 *       oldest first. Capped at {@link #MAX_EVENTS} so a chatty stack
 *       (media start-up) doesn't grow the array unbounded. Backend
 *       already caps its ring buffer at 200; the same limit here keeps
 *       memory + reactivity work bounded on the client.</li>
 *   <li>{@code error} — last observed error string; null when healthy.</li>
 *   <li>{@code source} — {@code 'sse' | 'poll' | 'idle'}. Dev-only badge
 *       hint; UI copy should not mention polling.</li>
 * </ul>
 *
 * <p>Empty state: the composable returns an empty array before first
 * paint; callers should render the "Nothing has changed recently."
 * copy without special-casing SSE vs poll.
 *
 * <p>Tab visibility: both loops pause when {@code document.hidden}, on
 * resume the SSE is re-opened. Prevents a hidden tab from sampling a
 * homelab box every 2s.
 */
export interface ContainerEventsStream {
  events: Ref<ContainerEventItem[]>;
  error: Ref<string | null>;
  source: Ref<'sse' | 'poll' | 'idle'>;
}

/** Client-side cap; matches DockerEventService.BUFFER_MAX. */
const MAX_EVENTS = 200;

export function useContainerEvents(): ContainerEventsStream {
  const events = ref<ContainerEventItem[]>([]);
  const error = ref<string | null>(null);
  const source = ref<'sse' | 'poll' | 'idle'>('idle');

  let eventSource: EventSource | null = null;
  let pollTimer: ReturnType<typeof setInterval> | null = null;
  let visibilityHandler: (() => void) | null = null;
  const failureTimestamps: number[] = [];
  const FAILURE_WINDOW_MS = 30_000;
  const FAILURE_THRESHOLD = 3;
  const POLL_INTERVAL_MS = 5_000;

  function pushEvent(e: ContainerEventItem): void {
    // Dedupe protection: SSE stream replays the buffer on subscribe, and
    // the poll fallback returns the same buffer. If we re-connect after a
    // failure, we might see events we've already recorded — key on
    // (ts, container, action) which is the only stable identity docker
    // gives us. Cheap because the check is against the tail (recent
    // events are the ones that could collide).
    const tail = events.value[events.value.length - 1];
    if (
      tail !== undefined &&
      tail.ts === e.ts &&
      tail.container === e.container &&
      tail.action === e.action
    ) {
      return;
    }
    events.value.push(e);
    if (events.value.length > MAX_EVENTS) {
      events.value.splice(0, events.value.length - MAX_EVENTS);
    }
  }

  function replaceEvents(list: ContainerEventItem[]): void {
    // Poll refresh path: the backend already returns oldest-first. Trust
    // that shape and swap wholesale so the reactive array watches once.
    events.value = list.slice(-MAX_EVENTS);
  }

  function stopPoll() {
    if (pollTimer !== null) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
  }

  function closeSse() {
    if (eventSource !== null) {
      eventSource.close();
      eventSource = null;
    }
  }

  async function poll(): Promise<void> {
    try {
      const res = await ContainersApi.recentEvents();
      replaceEvents(res);
      error.value = null;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'poll failed';
    }
  }

  function startPoll(): void {
    stopPoll();
    source.value = 'poll';
    void poll();
    pollTimer = setInterval(() => {
      if (document.hidden) return;
      void poll();
    }, POLL_INTERVAL_MS);
  }

  function recordFailureAndMaybeFallback(): void {
    const now = Date.now();
    failureTimestamps.push(now);
    while (
      failureTimestamps.length > 0 &&
      now - failureTimestamps[0] > FAILURE_WINDOW_MS
    ) {
      failureTimestamps.shift();
    }
    if (failureTimestamps.length >= FAILURE_THRESHOLD) {
      closeSse();
      startPoll();
    } else {
      const delayMs = 1_000 * failureTimestamps.length;
      setTimeout(() => {
        if (source.value === 'sse' || source.value === 'idle') startSse();
      }, delayMs);
    }
  }

  function startSse(): void {
    closeSse();
    try {
      eventSource = openContainerEventsStream(
        (e) => {
          pushEvent(e);
          error.value = null;
          failureTimestamps.length = 0;
        },
        () => {
          recordFailureAndMaybeFallback();
        },
      );
    } catch {
      startPoll();
      return;
    }
    source.value = 'sse';
  }

  onMounted(() => {
    startSse();
    visibilityHandler = () => {
      if (document.hidden) {
        closeSse();
        stopPoll();
        source.value = 'idle';
      } else if (source.value === 'idle') {
        startSse();
      }
    };
    document.addEventListener('visibilitychange', visibilityHandler);
  });

  onScopeDispose(() => {
    closeSse();
    stopPoll();
    if (visibilityHandler !== null) {
      document.removeEventListener('visibilitychange', visibilityHandler);
      visibilityHandler = null;
    }
    source.value = 'idle';
  });

  return { events, error, source };
}
