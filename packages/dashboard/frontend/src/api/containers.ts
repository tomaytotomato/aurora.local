// B1 (v0.3, iter-9): API surface for container-lifecycle events, backed
// by DockerEventService on the backend. Two endpoints:
//   • GET /api/containers/events         → snapshot of the ring buffer
//   • GET /api/containers/events/stream  → SSE, replays buffer then live
//
// Payload shape mirrors DockerEventService.ContainerEvent.toMap():
//   { ts: number (ms), container: string, action: string, image?: string }
//
// The stream endpoint is authenticated (session cookie) — same posture as
// /api/containers itself. Event source subscription is same-origin, so
// cookies flow without explicit `withCredentials`.

import { http } from './client';

export interface ContainerEventItem {
  /** Unix milliseconds — backend normalises docker's seconds already. */
  ts: number;
  /** Container name (from Event.actor.attributes.name, or id fallback). */
  container: string;
  /**
   * Normalised action. Lifecycle actions surface verbatim (create, start,
   * stop, restart, pause, unpause, die, kill, destroy, oom); health probe
   * events collapse to `health:healthy` / `health:unhealthy` so the frontend
   * can render a stable icon per verdict.
   */
  action: string;
  /** Image ref (Event.from). Omitted when the daemon didn't populate it. */
  image?: string;
}

export interface ContainerLogLine {
  /** RFC3339 timestamp docker prefixed the line with; may be absent. */
  ts?: string;
  /** {@code 'stdout'} or {@code 'stderr'} depending on the frame. */
  stream: 'stdout' | 'stderr';
  /** Log text; already whitespace-normalised (no trailing newline). */
  line: string;
}

export interface ContainerLogsResponse {
  container_id: string;
  tail: number;
  /** True when the byte-cap on the backend collector was hit. */
  truncated: boolean;
  lines: ContainerLogLine[];
}

export const ContainersApi = {
  /** Poll fallback / initial snapshot. Oldest first. */
  async recentEvents(): Promise<ContainerEventItem[]> {
    const { data } = await http.get<ContainerEventItem[]>('/containers/events');
    return data;
  },

  /**
   * B3 (v0.3): snapshot tail of a container's logs. Default 200 lines.
   * Backend caps to [1, 2000] and clamps at a 2 MiB byte budget.
   */
  async logs(id: string, tail: number = 200): Promise<ContainerLogsResponse> {
    const { data } = await http.get<ContainerLogsResponse>(
      `/containers/${encodeURIComponent(id)}/logs`,
      { params: { tail } },
    );
    return data;
  },
};

/**
 * Open an EventSource against the container-events stream. Callers
 * receive one {@code container-event} message per emission; malformed
 * payloads are silently dropped so the stream stays alive.
 *
 * <p>Returns the raw {@link EventSource} so lifecycle (close, addListener)
 * is under the caller's control. The composable
 * {@code useContainerEvents} wraps this with the failure ladder + poll
 * fallback used everywhere else in the app.
 */
export function openContainerEventsStream(
  onEvent: (e: ContainerEventItem) => void,
  onError?: (e: Event) => void,
): EventSource {
  // withCredentials: same-origin ⇒ cookies flow automatically. Setting it
  // true forces a CORS preflight-ish handshake we don't need.
  const es = new EventSource('/api/containers/events/stream');
  es.addEventListener('container-event', (msg: MessageEvent) => {
    try {
      onEvent(JSON.parse(msg.data) as ContainerEventItem);
    } catch {
      // Bad payload — keep the stream alive.
    }
  });
  if (onError) es.onerror = onError;
  return es;
}
