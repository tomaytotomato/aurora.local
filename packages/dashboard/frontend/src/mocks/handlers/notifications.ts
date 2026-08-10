import { http, HttpResponse } from 'msw';

import type { ChannelDraft, NotificationChannel } from '@/api/notifications';

import { state } from '../state';
import { FAILING_TARGET_FRAGMENT } from '../fixtures/notifications';
import { noContent, nowIso } from './shared';

export const notificationsHandlers = [
  http.get('/api/notifications/channels', () => HttpResponse.json(state.notifications.channels)),

  http.post('/api/notifications/channels', async ({ request }) => {
    const draft = (await request.json()) as ChannelDraft;
    const channel: NotificationChannel = {
      id: 'chan-' + Math.random().toString(36).slice(2, 8),
      kind: draft.kind,
      name: draft.name,
      enabled: true,
      target: draft.target,
      events: draft.events,
      lastSentAt: null,
      lastResult: null,
      lastError: null,
    };
    state.notifications.channels = [...state.notifications.channels, channel];
    return HttpResponse.json(channel, { status: 201 });
  }),

  http.patch('/api/notifications/channels/:id', async ({ params, request }) => {
    const patch = (await request.json()) as Partial<ChannelDraft> & { enabled?: boolean };
    const channel = state.notifications.channels.find((c) => c.id === String(params.id));
    if (!channel) return new HttpResponse(null, { status: 404 });
    Object.assign(channel, patch);
    return HttpResponse.json(channel);
  }),

  http.delete('/api/notifications/channels/:id', ({ params }) => {
    state.notifications.channels = state.notifications.channels.filter((c) => c.id !== String(params.id));
    return noContent();
  }),

  // A test send reports what actually happened rather than always
  // claiming success, so the "this channel is quietly broken" path is
  // reachable in dev.
  http.post('/api/notifications/channels/:id/test', ({ params }) => {
    const channel = state.notifications.channels.find((c) => c.id === String(params.id));
    if (!channel) return new HttpResponse(null, { status: 404 });

    const fails = channel.target.includes(FAILING_TARGET_FRAGMENT);
    const error = fails ? '404 Unknown Webhook — it was probably deleted in Discord.' : null;

    channel.lastSentAt = nowIso();
    channel.lastResult = fails ? 'failed' : 'ok';
    channel.lastError = error;

    state.notifications.deliveries = [
      {
        id: 'del-' + Math.random().toString(36).slice(2, 8),
        channelId: channel.id,
        event: 'job-failed',
        subject: 'Test message from Aurora',
        sentAt: channel.lastSentAt,
        result: channel.lastResult,
        error,
      },
      ...state.notifications.deliveries,
    ];

    return HttpResponse.json({ result: channel.lastResult, error });
  }),

  http.get('/api/notifications/history', () => HttpResponse.json(state.notifications.deliveries)),
];
