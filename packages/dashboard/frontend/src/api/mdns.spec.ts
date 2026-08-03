import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import axios, { AxiosError, type AxiosAdapter, type AxiosResponse } from 'axios';
import { http, _resetToastDedupe } from './client';
import { MdnsApi, type MdnsAliasPayload } from './mdns';
import { dismissAll, useToastQueue } from '@/composables/useToast';

/**
 * mDNS API client — sanity checks around the two endpoints + the
 * `toast: false` opt-out on reconcile (Settings card renders its own
 * inline state; the global 5xx toast would double-announce).
 */

const PAYLOAD_SAMPLE: MdnsAliasPayload = {
  aliases: [
    {
      alias: 'notes.aurora.local',
      label: 'notes',
      pkg: 'notes',
      source: 'manifest',
      state: 'up',
      targetIp: '192.168.0.110',
      publishedAt: '2026-08-03T14:15:00Z',
      error: null,
    },
    {
      alias: 'sonarr.aurora.local',
      label: 'sonarr',
      pkg: 'media',
      source: 'caddy',
      state: 'starting',
      targetIp: '192.168.0.110',
      publishedAt: null,
      error: null,
    },
  ],
  total: 2,
  up: 1,
  failed: 0,
};

function installAdapter(mode: 'ok' | { status: number }): void {
  const adapter: AxiosAdapter = (config) => {
    if (mode === 'ok') {
      return Promise.resolve({
        data: PAYLOAD_SAMPLE,
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      } as AxiosResponse);
    }
    const err = new AxiosError('boom', 'ERR_BAD_RESPONSE', config);
    err.response = {
      status: mode.status, statusText: 'err', data: null, headers: {}, config,
    } as AxiosResponse;
    return Promise.reject(err);
  };
  http.defaults.adapter = adapter;
}

beforeEach(() => {
  dismissAll();
  _resetToastDedupe();
});
afterEach(() => {
  dismissAll();
  _resetToastDedupe();
  delete (http.defaults as { adapter?: AxiosAdapter }).adapter;
});

describe('MdnsApi', () => {
  it('list() returns the payload as-is', async () => {
    installAdapter('ok');
    const p = await MdnsApi.list();
    expect(p.total).toBe(2);
    expect(p.up).toBe(1);
    expect(p.aliases).toHaveLength(2);
    expect(p.aliases[0].alias).toBe('notes.aurora.local');
    expect(p.aliases[0].source).toBe('manifest');
    expect(p.aliases[1].state).toBe('starting');
  });

  it('reconcile() returns the fresh payload', async () => {
    installAdapter('ok');
    const p = await MdnsApi.reconcile();
    expect(p.up).toBe(1);
  });

  it('reconcile() 500 does NOT raise the global destructive toast (opt-out)', async () => {
    installAdapter({ status: 500 });
    await expect(MdnsApi.reconcile()).rejects.toBeInstanceOf(AxiosError);
    // Global interceptor auto-raises on 5xx but the reconcile call
    // passes toast: false, so the queue stays empty. The Settings card
    // renders the error inline via mdnsErr / humanCopyForError.
    expect(useToastQueue().queue).toHaveLength(0);
  });

  it('list() 500 DOES raise the global destructive toast (no opt-out)', async () => {
    installAdapter({ status: 500 });
    await expect(MdnsApi.list()).rejects.toBeInstanceOf(AxiosError);
    // list() does not opt out, so the axios interceptor still surfaces
    // a destructive toast (Sarah sees "Server error" bottom-right even
    // if she was on a different tab).
    expect(useToastQueue().queue).toHaveLength(1);
    expect(useToastQueue().queue[0].variant).toBe('destructive');
  });
});
