import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import axios, { AxiosError, type AxiosAdapter, type AxiosResponse } from 'axios';
import { http, _resetToastDedupe } from './client';
import { useToastQueue, dismissAll } from '@/composables/useToast';

/**
 * C10 iter-19: axios interceptor + Toast integration.
 *
 * Uses a hand-rolled adapter (no axios-mock-adapter dep) so we don't
 * grow package.json for one spec. Each test installs an adapter that
 * resolves or rejects with a specified AxiosResponse / AxiosError,
 * then checks the useToast queue.
 *
 * Confirms:
 *   - 5xx → destructive 'Server error' toast.
 *   - Status 0 (network drop / CORS / timeout) → 'Network trouble' toast.
 *   - 4xx (400 / 404 / 409) → no toast (callers surface inline).
 *   - 401 → no toast (login redirect owns it).
 *   - Per-request `toast: false` opts out.
 *   - Per-request `toast: { title, description }` overrides copy.
 *   - Rapid identical failures deduped inside a 5-second window.
 */

function installAdapter(
  mode: 'ok' | { status: number } | { network: true } | { timeout: true },
): void {
  const adapter: AxiosAdapter = (config) => {
    if (mode === 'ok') {
      return Promise.resolve({
        data: {},
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      } as AxiosResponse);
    }
    // Build the AxiosError with the ORIGINAL config attached so the
    // interceptor sees the caller's toast:false / toast:{...} override.
    const err = new AxiosError(
      'network' in mode ? 'Network Error' : 'timeout' in mode ? 'timeout' : 'boom',
      'ERR_BAD_RESPONSE',
      config,
    );
    if ('status' in mode) {
      err.response = {
        status: mode.status,
        statusText: 'err',
        data: null,
        headers: {},
        config,
      } as AxiosResponse;
    }
    return Promise.reject(err);
  };
  http.defaults.adapter = adapter;
}

beforeEach(() => {
  dismissAll();
  _resetToastDedupe();
  Object.defineProperty(window, 'location', {
    writable: true,
    value: { pathname: '/foo', href: '' },
  });
});

afterEach(() => {
  dismissAll();
  _resetToastDedupe();
  // Reset adapter so subsequent describe blocks start clean.
  delete (http.defaults as { adapter?: AxiosAdapter }).adapter;
});

describe('axios interceptor — toast on failure', () => {
  it('500 raises a destructive toast', async () => {
    installAdapter({ status: 500 });
    await expect(http.get('/x')).rejects.toBeInstanceOf(AxiosError);
    const q = useToastQueue();
    expect(q.queue).toHaveLength(1);
    expect(q.queue[0].variant).toBe('destructive');
    expect(q.queue[0].title).toBe('Server error');
    expect(q.queue[0].description).toContain('server error');
  });

  it('503 also raises', async () => {
    installAdapter({ status: 503 });
    await expect(http.get('/x')).rejects.toBeInstanceOf(AxiosError);
    expect(useToastQueue().queue).toHaveLength(1);
  });

  it('network error (status 0) raises a "Network trouble" toast', async () => {
    installAdapter({ network: true });
    await expect(http.get('/x')).rejects.toBeInstanceOf(AxiosError);
    const q = useToastQueue();
    expect(q.queue).toHaveLength(1);
    expect(q.queue[0].title).toBe('Network trouble');
    expect(q.queue[0].description).toContain("couldn't reach");
  });

  it('timeout (status 0) raises a "Network trouble" toast', async () => {
    installAdapter({ timeout: true });
    await expect(http.get('/x')).rejects.toBeInstanceOf(AxiosError);
    expect(useToastQueue().queue).toHaveLength(1);
    expect(useToastQueue().queue[0].title).toBe('Network trouble');
  });

  it('400 does NOT raise a toast (caller surfaces inline)', async () => {
    installAdapter({ status: 400 });
    await expect(http.get('/x')).rejects.toBeInstanceOf(AxiosError);
    expect(useToastQueue().queue).toHaveLength(0);
  });

  it('404 does NOT raise a toast', async () => {
    installAdapter({ status: 404 });
    await expect(http.get('/x')).rejects.toBeInstanceOf(AxiosError);
    expect(useToastQueue().queue).toHaveLength(0);
  });

  it('409 does NOT raise a toast (conflict is expected)', async () => {
    installAdapter({ status: 409 });
    await expect(http.get('/x')).rejects.toBeInstanceOf(AxiosError);
    expect(useToastQueue().queue).toHaveLength(0);
  });

  it('401 does NOT raise a toast (login redirect owns it)', async () => {
    installAdapter({ status: 401 });
    await expect(http.get('/x')).rejects.toBeInstanceOf(AxiosError);
    expect(useToastQueue().queue).toHaveLength(0);
  });

  it('per-request `toast: false` opts out of the auto-toast on 5xx', async () => {
    installAdapter({ status: 500 });
    await expect(http.get('/x', { toast: false })).rejects.toBeInstanceOf(AxiosError);
    expect(useToastQueue().queue).toHaveLength(0);
  });

  it('per-request `toast: { title, description }` overrides copy', async () => {
    installAdapter({ status: 500 });
    await expect(
      http.get('/x', {
        toast: { title: 'Restart failed', description: 'The container refused to restart.' },
      }),
    ).rejects.toBeInstanceOf(AxiosError);
    const q = useToastQueue();
    expect(q.queue).toHaveLength(1);
    expect(q.queue[0].title).toBe('Restart failed');
    expect(q.queue[0].description).toBe('The container refused to restart.');
  });

  it('dedupes identical failures inside a 5-second window', async () => {
    // Freeze Date.now() so the dedupe cache sees a stable clock while
    // we fire three back-to-back failures.
    const t0 = 1_700_000_000_000;
    const nowSpy = vi.spyOn(Date, 'now').mockReturnValue(t0);
    installAdapter({ status: 500 });

    await expect(http.get('/x')).rejects.toBeInstanceOf(AxiosError);
    await expect(http.get('/x')).rejects.toBeInstanceOf(AxiosError);
    await expect(http.get('/x')).rejects.toBeInstanceOf(AxiosError);
    expect(useToastQueue().queue).toHaveLength(1);

    // Still inside the 5-second window.
    nowSpy.mockReturnValue(t0 + 4999);
    await expect(http.get('/x')).rejects.toBeInstanceOf(AxiosError);
    expect(useToastQueue().queue).toHaveLength(1);

    // Past the window — new failure emits again.
    nowSpy.mockReturnValue(t0 + 6000);
    await expect(http.get('/x')).rejects.toBeInstanceOf(AxiosError);
    expect(useToastQueue().queue).toHaveLength(2);

    nowSpy.mockRestore();
  });
});
