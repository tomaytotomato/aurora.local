import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import axios, { AxiosError, type AxiosAdapter, type AxiosResponse } from 'axios';
import { http, _resetToastDedupe } from './client';
import { UsersApi, type UserSummary } from './users';
import { dismissAll, useToastQueue } from '@/composables/useToast';

/**
 * Phase D iter-10 (D9): /api/users client wiring.
 *
 * Confirms:
 *   - list/create/update/delete hit the expected paths + methods.
 *   - Mutations opt out of the global 5xx toast (form renders inline
 *     error copy; the toast would double-announce).
 */

let capturedRequest: { url?: string; method?: string; body?: unknown } = {};

function installOkAdapter(payload: UserSummary | UserSummary[] | void = undefined): void {
  capturedRequest = {};
  const adapter: AxiosAdapter = (config) => {
    capturedRequest = {
      url: config.url,
      method: config.method,
      body: config.data ? JSON.parse(String(config.data)) : undefined,
    };
    return Promise.resolve({
      data: payload,
      status: payload === undefined ? 204 : 200,
      statusText: 'OK',
      headers: {},
      config,
    } as AxiosResponse);
  };
  http.defaults.adapter = adapter;
}

function installErrorAdapter(status: number): void {
  const adapter: AxiosAdapter = (config) => {
    const err = new AxiosError('boom', 'ERR_BAD_RESPONSE', config);
    err.response = {
      status, statusText: 'err', data: null, headers: {}, config,
    } as AxiosResponse;
    return Promise.reject(err);
  };
  http.defaults.adapter = adapter;
}

const sampleUser: UserSummary = {
  id: 1,
  username: 'bruce',
  role: 'admin',
  tz: 'UTC',
  createdAt: '2026-01-01T00:00:00Z',
};

beforeEach(() => {
  dismissAll();
  _resetToastDedupe();
});
afterEach(() => {
  dismissAll();
  _resetToastDedupe();
  delete (http.defaults as { adapter?: AxiosAdapter }).adapter;
});

describe('UsersApi', () => {
  it('list() calls GET /users', async () => {
    installOkAdapter([sampleUser]);
    const rows = await UsersApi.list();
    expect(rows).toHaveLength(1);
    expect(rows[0].username).toBe('bruce');
    expect(capturedRequest.url).toBe('/users');
    expect(capturedRequest.method).toBe('get');
  });

  it('create() calls POST /users with body + opts out of the global toast', async () => {
    installOkAdapter(sampleUser);
    await UsersApi.create({
      username: 'alice',
      password: 'reallystrong-2026',
      role: 'user',
      tz: 'Europe/London',
    });
    expect(capturedRequest.method).toBe('post');
    expect(capturedRequest.url).toBe('/users');
    expect(capturedRequest.body).toMatchObject({
      username: 'alice',
      role: 'user',
      tz: 'Europe/London',
    });
  });

  it('update() calls PUT /users/{id}', async () => {
    installOkAdapter(sampleUser);
    await UsersApi.update(2, { role: 'admin' });
    expect(capturedRequest.method).toBe('put');
    expect(capturedRequest.url).toBe('/users/2');
    expect(capturedRequest.body).toEqual({ role: 'admin' });
  });

  it('remove() calls DELETE /users/{id}', async () => {
    installOkAdapter();
    await UsersApi.remove(3);
    expect(capturedRequest.method).toBe('delete');
    expect(capturedRequest.url).toBe('/users/3');
  });

  it('create() 409 DOES NOT raise the global destructive toast', async () => {
    // 4xx doesn't fire the toast per axios interceptor policy — but
    // even if it did, the form's toast: false opt-out would silence
    // it. Belt-and-braces: 500 also stays quiet on mutations so the
    // Dialog inline error is the single source of truth.
    installErrorAdapter(500);
    await expect(UsersApi.create({
      username: 'alice',
      password: 'reallystrong-2026',
      role: 'user',
    })).rejects.toBeInstanceOf(AxiosError);
    expect(useToastQueue().queue).toHaveLength(0);
  });

  it('update() 500 DOES NOT raise the global destructive toast either', async () => {
    installErrorAdapter(500);
    await expect(UsersApi.update(1, { role: 'admin' })).rejects.toBeInstanceOf(AxiosError);
    expect(useToastQueue().queue).toHaveLength(0);
  });

  it('remove() 500 DOES NOT raise the global destructive toast', async () => {
    installErrorAdapter(500);
    await expect(UsersApi.remove(1)).rejects.toBeInstanceOf(AxiosError);
    expect(useToastQueue().queue).toHaveLength(0);
  });

  it('list() 500 STILL raises the global toast (no opt-out, background load)', async () => {
    installErrorAdapter(500);
    await expect(UsersApi.list()).rejects.toBeInstanceOf(AxiosError);
    // list() runs on mount without a Dialog to render an inline
    // error → the global toast is the correct surface.
    expect(useToastQueue().queue).toHaveLength(1);
    expect(useToastQueue().queue[0].variant).toBe('destructive');
  });
});
