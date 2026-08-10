// Outbound notifications.
//
// Aurora already detects every one of these events — a service falling
// over, a security finding, a backup failing, a drive going bad — and
// tells nobody. You find out by opening the dashboard, which means you
// find out when you happen to look, which for a home server is usually
// three weeks later.
//
// Three channel kinds, chosen because they cover the realistic cases
// without an account or an SMTP server: ntfy (self-hosted or ntfy.sh, and
// the only one that gets a notification onto a phone with no signup),
// Discord (where a lot of homelabbers already live), and a plain webhook
// for anything else.
//
// Email is deliberately absent. A working MTA on a home box is a
// multi-hour yak shave that ends in a Gmail spam folder.

import { http } from './client';

export type ChannelKind = 'ntfy' | 'discord' | 'webhook';

export type NotifyEvent =
  | 'service-down'
  | 'security-finding'
  | 'backup-failed'
  | 'backup-stale'
  | 'disk-health'
  | 'update-available'
  | 'job-failed';

export interface NotificationChannel {
  id: string;
  kind: ChannelKind;
  name: string;
  enabled: boolean;
  /** ntfy topic URL, Discord webhook URL, or any POST target. */
  target: string;
  events: NotifyEvent[];
  lastSentAt: string | null;
  lastResult: 'ok' | 'failed' | null;
  lastError: string | null;
}

export interface NotificationDelivery {
  id: string;
  channelId: string;
  event: NotifyEvent;
  subject: string;
  sentAt: string;
  result: 'ok' | 'failed';
  error: string | null;
}

export interface ChannelDraft {
  kind: ChannelKind;
  name: string;
  target: string;
  events: NotifyEvent[];
}

/** Every event, in the order they appear in the UI. */
export const NOTIFY_EVENTS: readonly NotifyEvent[] = [
  'service-down',
  'backup-failed',
  'backup-stale',
  'disk-health',
  'security-finding',
  'job-failed',
  'update-available',
] as const;

interface EventCopy {
  label: string;
  description: string;
  /** On by default for a new channel. */
  defaultOn: boolean;
}

const EVENT_COPY: Record<NotifyEvent, EventCopy> = {
  'service-down': {
    label: 'An app stops responding',
    description: 'Its health probe fails and it does not come back on its own.',
    defaultOn: true,
  },
  'backup-failed': {
    label: 'A backup fails',
    description: 'The run errored. The previous snapshot is still there.',
    defaultOn: true,
  },
  'backup-stale': {
    label: 'Backups stop happening',
    description: 'Nothing has succeeded inside the staleness window on the Backup page.',
    defaultOn: true,
  },
  'disk-health': {
    label: 'A drive reports a problem',
    description: 'Failing SMART status, or reallocated sectors appearing.',
    defaultOn: true,
  },
  'security-finding': {
    label: 'A security finding is raised',
    description: 'Only new ones, and only high or medium.',
    defaultOn: true,
  },
  'job-failed': {
    label: 'An action fails',
    description: 'An update, restore or parity sync that you started did not finish.',
    defaultOn: false,
  },
  'update-available': {
    label: 'An app update is available',
    description: 'Chatty by nature — off unless you want it.',
    defaultOn: false,
  },
};

export function eventLabel(event: NotifyEvent): string {
  return EVENT_COPY[event]?.label ?? event;
}

export function eventDescription(event: NotifyEvent): string {
  return EVENT_COPY[event]?.description ?? '';
}

/** Sensible starting selection for a new channel. */
export function defaultEvents(): NotifyEvent[] {
  return NOTIFY_EVENTS.filter((e) => EVENT_COPY[e].defaultOn);
}

interface KindCopy {
  label: string;
  placeholder: string;
  help: string;
}

const KIND_COPY: Record<ChannelKind, KindCopy> = {
  ntfy: {
    label: 'ntfy',
    placeholder: 'https://ntfy.sh/my-aurora-topic',
    help: 'Pick a topic name nobody else would guess — on the public ntfy.sh, the topic is the only thing keeping your alerts private.',
  },
  discord: {
    label: 'Discord',
    placeholder: 'https://discord.com/api/webhooks/…',
    help: 'Server Settings → Integrations → Webhooks → New Webhook, then copy the URL.',
  },
  webhook: {
    label: 'Webhook',
    placeholder: 'https://example.com/hooks/aurora',
    help: 'Aurora POSTs a small JSON body: event, subject, detail, and a timestamp.',
  },
};

export function kindLabel(kind: ChannelKind): string {
  return KIND_COPY[kind]?.label ?? kind;
}

export function kindPlaceholder(kind: ChannelKind): string {
  return KIND_COPY[kind]?.placeholder ?? '';
}

export function kindHelp(kind: ChannelKind): string {
  return KIND_COPY[kind]?.help ?? '';
}

/**
 * Field errors for a channel form, keyed by field name. Empty object
 * means valid. Kept here rather than in the component so the rules are
 * testable and the same on create and edit.
 */
export function validateChannel(draft: ChannelDraft): Record<string, string> {
  const errors: Record<string, string> = {};

  if (!draft.name.trim()) {
    errors.name = 'Give this somewhere a name so you can tell them apart.';
  }

  const target = draft.target.trim();
  if (!target) {
    errors.target = 'Where should Aurora send it?';
  } else if (!/^https?:\/\/.+/i.test(target)) {
    errors.target = 'That needs to be a full URL starting with http:// or https://.';
  } else if (draft.kind === 'discord' && !/discord(app)?\.com\/api\/webhooks\//i.test(target)) {
    errors.target = "That doesn't look like a Discord webhook URL.";
  }

  if (!draft.events.length) {
    errors.events = 'Pick at least one thing to be told about.';
  }

  return errors;
}

/** True when a channel is configured but switched off — worth saying so. */
export function isMuted(channel: NotificationChannel): boolean {
  return !channel.enabled;
}

/** Channels that exist, are on, and last failed to deliver. */
export function failingChannels(channels: NotificationChannel[]): NotificationChannel[] {
  return channels.filter((c) => c.enabled && c.lastResult === 'failed');
}

export const NotificationsApi = {
  async channels(): Promise<NotificationChannel[]> {
    const { data } = await http.get<NotificationChannel[]>('/notifications/channels');
    return data;
  },
  async create(draft: ChannelDraft): Promise<NotificationChannel> {
    const { data } = await http.post<NotificationChannel>('/notifications/channels', draft);
    return data;
  },
  async update(id: string, patch: Partial<ChannelDraft> & { enabled?: boolean }): Promise<NotificationChannel> {
    const { data } = await http.patch<NotificationChannel>(
      `/notifications/channels/${encodeURIComponent(id)}`,
      patch,
    );
    return data;
  },
  async remove(id: string): Promise<void> {
    await http.delete(`/notifications/channels/${encodeURIComponent(id)}`);
  },
  /** Send a test message now. Resolves with what actually happened. */
  async test(id: string): Promise<{ result: 'ok' | 'failed'; error: string | null }> {
    const { data } = await http.post<{ result: 'ok' | 'failed'; error: string | null }>(
      `/notifications/channels/${encodeURIComponent(id)}/test`,
    );
    return data;
  },
  async history(): Promise<NotificationDelivery[]> {
    const { data } = await http.get<NotificationDelivery[]>('/notifications/history');
    return data;
  },
};
