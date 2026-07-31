// Docker/system SSE stream. See DASHBOARD_BRIEF §4.4 + §7.
// Consumer opens one connection; the events store below fans out.

export interface DockerEvent {
  kind: 'docker';
  action: string; // start | stop | die | health_status
  container: string;
  image?: string;
  service?: string;
  ts: number;
}

export interface JobEvent {
  kind: 'job';
  jobId: string;
  phase: 'started' | 'progress' | 'log' | 'done' | 'failed';
  message?: string;
  progress?: number; // 0..1
  ts: number;
}

export interface SystemEvent {
  kind: 'system';
  event: string;
  ts: number;
}

export type AuroraEvent = DockerEvent | JobEvent | SystemEvent;

export function openEventStream(
  onEvent: (e: AuroraEvent) => void,
  onError?: (e: Event) => void,
): EventSource {
  const es = new EventSource('/api/events', { withCredentials: true });
  es.onmessage = (msg) => {
    try {
      onEvent(JSON.parse(msg.data) as AuroraEvent);
    } catch {
      // ignore malformed
    }
  };
  if (onError) es.onerror = onError;
  return es;
}
