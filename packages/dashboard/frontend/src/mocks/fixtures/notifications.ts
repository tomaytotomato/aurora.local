// Notification fixtures.
//
// Two channels out of the box: an ntfy topic that works, and a Discord
// webhook that last failed with a 404, which is what happens when
// somebody deletes the webhook in Discord and Aurora carries on posting
// into the void. That second one is the state worth designing for — a
// notification channel that has silently stopped working is worse than
// no channel at all, because you think you are covered.
//
// EDIT ME: set the Discord channel's lastResult to 'ok' for the calm
// version, or empty initialChannels() for the first-run empty state.

import type { NotificationChannel, NotificationDelivery } from '@/api/notifications';

function hoursAgo(h: number): string {
  return new Date(Date.now() - h * 3_600_000).toISOString();
}

export function initialChannels(): NotificationChannel[] {
  return [
    {
      id: 'chan-ntfy',
      kind: 'ntfy',
      name: 'Phone',
      enabled: true,
      target: 'https://ntfy.sh/aurora-bruce-7f3a91',
      events: ['service-down', 'backup-failed', 'backup-stale', 'disk-health', 'security-finding'],
      lastSentAt: hoursAgo(30),
      lastResult: 'ok',
      lastError: null,
    },
    {
      id: 'chan-discord',
      kind: 'discord',
      name: 'Homelab channel',
      enabled: true,
      target: 'https://discord.com/api/webhooks/1180000000000000000/abcdEFGH-ijklMNOP',
      events: ['service-down', 'job-failed'],
      lastSentAt: hoursAgo(30),
      lastResult: 'failed',
      lastError: '404 Unknown Webhook — it was probably deleted in Discord.',
    },
  ];
}

export function initialDeliveries(): NotificationDelivery[] {
  return [
    {
      id: 'del-1',
      channelId: 'chan-ntfy',
      event: 'disk-health',
      subject: '/dev/sdc has 3 reallocated sectors',
      sentAt: hoursAgo(30),
      result: 'ok',
      error: null,
    },
    {
      id: 'del-2',
      channelId: 'chan-discord',
      event: 'disk-health',
      subject: '/dev/sdc has 3 reallocated sectors',
      sentAt: hoursAgo(30),
      result: 'failed',
      error: '404 Unknown Webhook',
    },
    {
      id: 'del-3',
      channelId: 'chan-ntfy',
      event: 'service-down',
      subject: 'Media stopped responding',
      sentAt: hoursAgo(52),
      result: 'ok',
      error: null,
    },
    {
      id: 'del-4',
      channelId: 'chan-ntfy',
      event: 'backup-failed',
      subject: 'Backup of /data/git failed',
      sentAt: hoursAgo(54),
      result: 'ok',
      error: null,
    },
  ];
}

/**
 * Targets the mock refuses to deliver to, so the test button has a
 * failure path. Anything containing this fragment fails; the Discord
 * fixture above uses it.
 */
export const FAILING_TARGET_FRAGMENT = '1180000000000000000';
