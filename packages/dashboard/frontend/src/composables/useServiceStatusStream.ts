import { onMounted, onScopeDispose, ref, type Ref } from 'vue';
import { ServicesApi, type ServicesStatusResponse } from '@/api/services';

/**
 * TD1 (2026-08-02): drop the 5s poll cliff on the Done checklist and the
 * dashboard-home Packages card. Subscribes to
 * {@code GET /api/services/status/stream} (Server-Sent Events) and falls
 * back to 5s polling on {@code /api/services/status} if the stream fails
 * three times inside a 30s window.
 *
 * <p>Contract for callers:
 * <ul>
 *   <li>{@code data} — the latest {@link ServicesStatusResponse} or null
 *       before first paint. Callers should tolerate null on first render.</li>
 *   <li>{@code error} — the last observed error string (null when healthy).
 *       Set only when the fallback poll fails; a transient EventSource
 *       error does not surface here as long as recovery succeeds.</li>
 *   <li>{@code source} — {@code 'sse'} while the SSE is live,
 *       {@code 'poll'} once the fallback engages. Meant for the dev-only
 *       badge, not user-facing copy.</li>
 * </ul>
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>Subscribes on mount, disposes on unmount (guarded by
 *       {@link onScopeDispose} so callers using it inside {@code <script setup>}
 *       don't leak a listener when navigating away).</li>
 *   <li>SSE connection open → source='sse', errors reset.</li>
 *   <li>SSE {@code onerror} → close, count failure, retry after 1s of
 *       linear backoff up to 3s. If failures ≥ 3 within 30s → close, flip
 *       to poll fallback.</li>
 *   <li>Poll fallback → 5s cadence, no automatic re-attempt of the SSE
 *       (avoids ping-pong on a flapping proxy). A new hard reload of the
 *       page is the honest reset.</li>
 * </ul>
 *
 * <p>The tab-visibility hook pauses both loops when the tab is hidden so
 * an idle background tab doesn't sample the box every 2s.
 */
export interface ServiceStatusStream {
  data: Ref<ServicesStatusResponse | null>;
  error: Ref<string | null>;
  source: Ref<'sse' | 'poll' | 'idle'>;
}

export function useServiceStatusStream(): ServiceStatusStream {
  const data = ref<ServicesStatusResponse | null>(null);
  const error = ref<string | null>(null);
  const source = ref<'sse' | 'poll' | 'idle'>('idle');

  let eventSource: EventSource | null = null;
  let pollTimer: ReturnType<typeof setInterval> | null = null;
  let visibilityHandler: (() => void) | null = null;
  const failureTimestamps: number[] = [];
  const FAILURE_WINDOW_MS = 30_000;
  const FAILURE_THRESHOLD = 3;
  const POLL_INTERVAL_MS = 5_000;

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
      const res = await ServicesApi.status();
      data.value = res;
      error.value = null;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'poll failed';
    }
  }

  function startPoll(): void {
    stopPoll();
    source.value = 'poll';
    // Kick off an immediate sample so the UI updates within one HTTP RTT.
    void poll();
    pollTimer = setInterval(() => {
      if (document.hidden) return;
      void poll();
    }, POLL_INTERVAL_MS);
  }

  function recordFailureAndMaybeFallback(): void {
    const now = Date.now();
    failureTimestamps.push(now);
    // Prune anything outside the sliding window.
    while (
      failureTimestamps.length > 0 &&
      now - failureTimestamps[0] > FAILURE_WINDOW_MS
    ) {
      failureTimestamps.shift();
    }
    if (failureTimestamps.length >= FAILURE_THRESHOLD) {
      // Give up on SSE for this session; poll is the honest reset.
      closeSse();
      startPoll();
    } else {
      // Linear back-off: 1s, 2s, 3s. Simple; a homelab box rarely
      // benefits from anything cleverer.
      const delayMs = 1_000 * failureTimestamps.length;
      setTimeout(() => {
        if (source.value === 'sse' || source.value === 'idle') startSse();
      }, delayMs);
    }
  }

  function startSse(): void {
    closeSse();
    try {
      // withCredentials false: the SSE endpoint is permitAll on the
      // backend, and forcing credentials here upsets CORS-less setups.
      // Same-origin ⇒ cookies flow regardless.
      eventSource = new EventSource('/api/services/status/stream');
    } catch (e) {
      // No EventSource in this runtime → fall straight to poll.
      startPoll();
      return;
    }
    source.value = 'sse';

    eventSource.addEventListener('service-status', (ev: MessageEvent) => {
      try {
        const payload = JSON.parse(ev.data) as ServicesStatusResponse;
        data.value = payload;
        error.value = null;
        failureTimestamps.length = 0; // healthy tick resets the window
      } catch (e) {
        // Bad payload isn't fatal — log and keep the stream alive.
        error.value = e instanceof Error ? e.message : 'malformed sse payload';
      }
    });

    eventSource.addEventListener('error', () => {
      // EventSource retries automatically on network errors, but that
      // hides a broken backend deploy from the user. We take control by
      // closing on the first error and running our own fallback ladder.
      recordFailureAndMaybeFallback();
    });
  }

  onMounted(() => {
    startSse();
    visibilityHandler = () => {
      if (document.hidden) {
        // Pause both loops when the tab is hidden — no reason to sample
        // if the user can't see the update.
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

  return { data, error, source };
}
