// Human copy for jobs. Kept out of the component so it can be tested
// without mounting anything, and so every surface that shows a job says
// the same thing about it.
//
// The rule these follow, same as lib/http-error-copy.ts: say what
// happened and what the operator can do, never show a raw stderr line as
// the headline. The log is right there underneath for the detail.

import type { JobFailureCode, JobKind, JobState } from '@/api/jobs';

/** Short present/past-tense headline for a job, by kind and state. */
export function jobHeadline(kind: JobKind, state: JobState): string {
  const phrasing: Record<JobKind, [running: string, success: string, failed: string]> = {
    enable: ['Adding the app…', 'App added', "Couldn't add the app"],
    disable: ['Removing the app…', 'App removed', "Couldn't remove the app"],
    update: ['Updating…', 'Update complete', 'Update failed'],
    'update-check': ['Checking for updates…', 'Check complete', "Couldn't check for updates"],
    start: ['Starting…', 'Started', "Couldn't start"],
    restart: ['Restarting…', 'Restarted', "Couldn't restart"],
    backup: ['Backing up…', 'Backup complete', 'Backup failed'],
    restore: ['Restoring…', 'Restore complete', 'Restore failed'],
    'parity-sync': ['Syncing parity…', 'Parity up to date', 'Parity sync failed'],
    'parity-scrub': ['Checking parity…', 'Parity verified', 'Parity check failed'],
    deploy: ['Deploying…', 'Deployed', 'Deploy failed'],
  };
  const [running, success, failed] = phrasing[kind] ?? ['Working…', 'Done', "Didn't finish"];
  if (state === 'success') return success;
  if (state === 'failed') return failed;
  return running;
}

const FAILURE_COPY: Record<Exclude<JobFailureCode, 'unknown'>, string> = {
  port_conflict:
    'Something else on this box is already listening on a port this app needs. Change the port in the app’s configuration, or stop whatever is holding it, then try again.',
  pull_rate_limited:
    'The image registry turned the download away for hitting its rate limit. Nothing on the box was changed, so the app is still on its current version. Try again in an hour.',
  registry_unreachable:
    "Aurora couldn't reach the image registry. Check the box's internet connection, then try again.",
  disk_full:
    'There is not enough free disk space to finish this. Free some space (the Disks page shows where it has gone) and try again.',
  docker_down:
    "Docker isn't responding on this box, so Aurora can't change anything. Everything already running is unaffected.",
  container_crashed:
    'The app started and then stopped again. The log below has the reason, and it is usually a missing setting.',
};

/**
 * A sentence explaining a failed job. Falls back to the job's own
 * `failureReason` when the code is unknown or absent, and to a generic
 * line when there is no reason either — never to an empty string, since
 * the caller renders this inside an alert.
 */
export function jobFailureCopy(code: JobFailureCode | null, reason: string | null): string {
  if (code && code !== 'unknown' && code in FAILURE_COPY) {
    return FAILURE_COPY[code as Exclude<JobFailureCode, 'unknown'>];
  }
  const trimmed = reason?.trim();
  if (trimmed) return trimmed;
  return 'Something went wrong and Aurora stopped. The log below has the details.';
}
