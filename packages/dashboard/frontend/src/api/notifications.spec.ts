import { describe, expect, it } from 'vitest';

import {
  NOTIFY_EVENTS,
  defaultEvents,
  eventDescription,
  eventLabel,
  failingChannels,
  isMuted,
  kindHelp,
  kindLabel,
  kindPlaceholder,
  validateChannel,
  type ChannelDraft,
  type NotificationChannel,
} from './notifications';

function draft(over: Partial<ChannelDraft> = {}): ChannelDraft {
  return {
    kind: 'ntfy',
    name: 'Phone',
    target: 'https://ntfy.sh/aurora-abc123',
    events: ['service-down'],
    ...over,
  };
}

function channel(over: Partial<NotificationChannel> & { id: string }): NotificationChannel {
  return {
    kind: 'ntfy',
    name: 'Phone',
    enabled: true,
    target: 'https://ntfy.sh/x',
    events: ['service-down'],
    lastSentAt: null,
    lastResult: null,
    lastError: null,
    ...over,
  };
}

describe('validateChannel', () => {
  it('accepts a well-formed channel', () => {
    expect(validateChannel(draft())).toEqual({});
  });

  it('insists on a name, so two channels can be told apart', () => {
    expect(validateChannel(draft({ name: '   ' })).name).toBeDefined();
  });

  it('insists on a destination', () => {
    expect(validateChannel(draft({ target: '' })).target).toBeDefined();
  });

  it('rejects a bare hostname, which is the usual paste mistake', () => {
    expect(validateChannel(draft({ target: 'ntfy.sh/aurora' })).target).toContain('http');
  });

  it('checks a Discord target actually looks like a Discord webhook', () => {
    const wrong = validateChannel(draft({ kind: 'discord', target: 'https://example.com/hook' }));
    expect(wrong.target).toContain('Discord');

    const right = validateChannel(
      draft({ kind: 'discord', target: 'https://discord.com/api/webhooks/123/abc' }),
    );
    expect(right.target).toBeUndefined();
  });

  it('accepts the discordapp.com form too, since old webhooks still use it', () => {
    const ok = validateChannel(
      draft({ kind: 'discord', target: 'https://discordapp.com/api/webhooks/123/abc' }),
    );
    expect(ok.target).toBeUndefined();
  });

  it('does not apply the Discord rule to a plain webhook', () => {
    expect(validateChannel(draft({ kind: 'webhook', target: 'https://example.com/hook' }))).toEqual({});
  });

  it('insists on at least one event, since a channel that reports nothing is not a channel', () => {
    expect(validateChannel(draft({ events: [] })).events).toBeDefined();
  });

  it('reports every problem at once rather than one at a time', () => {
    const errors = validateChannel(draft({ name: '', target: '', events: [] }));
    expect(Object.keys(errors).sort()).toEqual(['events', 'name', 'target']);
  });
});

describe('defaultEvents', () => {
  it('starts with the things that mean something is wrong', () => {
    const defaults = defaultEvents();
    expect(defaults).toContain('service-down');
    expect(defaults).toContain('backup-failed');
    expect(defaults).toContain('disk-health');
  });

  it('leaves the chatty ones off, so a new channel is not immediately annoying', () => {
    expect(defaultEvents()).not.toContain('update-available');
  });

  it('returns a fresh array each time, so one form cannot mutate the next', () => {
    const a = defaultEvents();
    a.push('update-available');
    expect(defaultEvents()).not.toContain('update-available');
  });
});

describe('copy helpers', () => {
  it('has a label and a description for every event', () => {
    for (const event of NOTIFY_EVENTS) {
      expect(eventLabel(event).length).toBeGreaterThan(0);
      expect(eventLabel(event)).not.toBe(event);
      expect(eventDescription(event).length).toBeGreaterThan(0);
    }
  });

  it('has a placeholder and help text for every kind', () => {
    for (const kind of ['ntfy', 'discord', 'webhook'] as const) {
      expect(kindLabel(kind).length).toBeGreaterThan(0);
      expect(kindPlaceholder(kind)).toContain('http');
      expect(kindHelp(kind).length).toBeGreaterThan(0);
    }
  });

  it('warns about the one genuinely surprising thing — a public ntfy topic is the only secret', () => {
    expect(kindHelp('ntfy')).toContain('guess');
  });
});

describe('failingChannels', () => {
  it('finds the channel that thinks it is on but is not delivering', () => {
    const rows = [
      channel({ id: 'ok', lastResult: 'ok' }),
      channel({ id: 'broken', lastResult: 'failed' }),
    ];
    expect(failingChannels(rows).map((c) => c.id)).toEqual(['broken']);
  });

  it('ignores a muted channel, which is not delivering on purpose', () => {
    const rows = [channel({ id: 'muted', enabled: false, lastResult: 'failed' })];
    expect(failingChannels(rows)).toEqual([]);
    expect(isMuted(rows[0])).toBe(true);
  });

  it('ignores a channel that has never sent anything', () => {
    expect(failingChannels([channel({ id: 'new' })])).toEqual([]);
  });
});
