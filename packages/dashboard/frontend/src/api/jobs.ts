// A job is any long-running operation Aurora runs on the operator's
// behalf that produces a log worth watching: adding an app, updating one,
// starting a stopped package, restoring a backup snapshot, deploying a
// custom stack.
//
// Before this existed, each of those either blocked on a spinner and a
// toast (Add app, Upgrade) or reported only its end state over the
// services SSE. That made a slow first pull look identical to a hang —
// Immich and Ollama routinely take minutes. Everything with a log now
// returns a job id, and `JobLogPanel.vue` streams it.
//
// The onboarding launch (POST /onboarding/launch) predates this and keeps
// its own endpoint and its own component: it shows per-package pills the
// generic panel has no concept of. Both stream the same `log` / `done`
// event names, so they can converge later without a wire change.

import { http } from './client';

export type JobKind =
  | 'enable'
  | 'disable'
  | 'update'
  | 'update-check'
  | 'start'
  | 'restart'
  | 'restore'
  | 'deploy';

export type JobState = 'queued' | 'running' | 'success' | 'failed';

/**
 * Machine-readable failure classification, mirroring the set the
 * onboarding launch already sends. The frontend picks copy from this
 * rather than showing a raw stderr line; `unknown` falls back to the
 * job's own `failureReason`.
 */
export type JobFailureCode =
  | 'port_conflict'
  | 'pull_rate_limited'
  | 'registry_unreachable'
  | 'disk_full'
  | 'docker_down'
  | 'container_crashed'
  | 'unknown';

export interface JobSummary {
  id: string;
  kind: JobKind;
  /** Package name, snapshot id, stack name — whatever the job acts on. Null for box-wide jobs. */
  target: string | null;
  state: JobState;
  startedAt: string;
  finishedAt: string | null;
  exitCode: number | null;
  failureCode: JobFailureCode | null;
  failureReason: string | null;
}

export interface JobStatus extends JobSummary {
  /** Log lines so far, oldest first. Capped server-side. */
  tail: string[];
}

/** A job has finished and will emit nothing further. */
export function isTerminal(state: JobState): boolean {
  return state === 'success' || state === 'failed';
}

/** Badge tone for a job state, matching the app's `Badge` vocabulary. */
export function jobTone(state: JobState): 'ok' | 'err' | 'neutral' {
  if (state === 'success') return 'ok';
  if (state === 'failed') return 'err';
  return 'neutral';
}

/**
 * How long a job has been running, or how long it took. Returns null when
 * the job has not started, so callers render nothing rather than "0s".
 */
export function jobElapsedMs(job: Pick<JobSummary, 'startedAt' | 'finishedAt'>, nowMs = Date.now()): number | null {
  const started = Date.parse(job.startedAt);
  if (!Number.isFinite(started)) return null;
  const ended = job.finishedAt ? Date.parse(job.finishedAt) : nowMs;
  if (!Number.isFinite(ended)) return null;
  return Math.max(0, ended - started);
}

/** "8s", "2m 04s". Short form, for a header line next to a spinner. */
export function formatElapsed(ms: number | null): string {
  if (ms === null) return '';
  const total = Math.floor(ms / 1000);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return m > 0 ? `${m}m ${String(s).padStart(2, '0')}s` : `${s}s`;
}

export const JobsApi = {
  async list(params: { state?: JobState; kind?: JobKind } = {}): Promise<JobSummary[]> {
    const { data } = await http.get<JobSummary[]>('/jobs', { params });
    return data;
  },
  async get(id: string): Promise<JobStatus> {
    const { data } = await http.get<JobStatus>(`/jobs/${encodeURIComponent(id)}`);
    return data;
  },
  /**
   * Live log. Emits `log` (one line per message), `ping` keepalives, and a
   * terminal `done` carrying the final JobStatus as JSON. Returns the raw
   * EventSource so the caller owns close(); `JobLogPanel.vue` wraps this.
   */
  openStream(id: string): EventSource {
    return new EventSource(`/api/jobs/${encodeURIComponent(id)}/stream`);
  },
};
