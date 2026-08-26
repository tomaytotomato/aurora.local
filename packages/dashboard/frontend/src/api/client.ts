import axios, { type AxiosInstance, AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { toast } from '@/composables/useToast';

// Extend AxiosRequestConfig so callers can opt out of the global
// interceptor's user-visible toast on 5xx / network failures. Set
// `toast: false` on requests where the caller renders its own error
// state (form submit, inline retry banner, silent background poll).
// Pass an object to override the default copy.
declare module 'axios' {
  export interface AxiosRequestConfig {
    toast?: false | { title?: string; description?: string };
  }
  export interface InternalAxiosRequestConfig {
    toast?: false | { title?: string; description?: string };
  }
}

// Thin axios wrapper. Same-origin, cookie-auth.
export const http: AxiosInstance = axios.create({
  baseURL: '/api',
  withCredentials: true,
  timeout: 15000,
  headers: {
    'X-Requested-With': 'aurora',
  },
});

// Dedupe cache — SSE / long-poll endpoints that flap during a network
// hiccup shouldn't queue ten identical destructive toasts. Description
// keyed, 5-second window.
const RECENT_TOAST_WINDOW_MS = 5000;
const recentToasts = new Map<string, number>();

function shouldRaiseToast(description: string): boolean {
  const now = Date.now();
  const last = recentToasts.get(description);
  if (last !== undefined && now - last < RECENT_TOAST_WINDOW_MS) return false;
  recentToasts.set(description, now);
  return true;
}

// Exported for tests + rare manual reset points.
export function _resetToastDedupe(): void {
  recentToasts.clear();
}

// Global 401 hook + toast-on-server-failure hook. Kept as one interceptor
// so the ordering (401 short-circuit before toast) stays obvious.
http.interceptors.response.use(
  (r) => r,
  (err: AxiosError) => {
    const status = err.response?.status ?? 0;
    const config = (err.config ?? {}) as InternalAxiosRequestConfig;

    if (status === 401) {
      const path = window.location.pathname;
      if (path !== '/login' && !path.startsWith('/onboarding')) {
        window.location.href = '/login';
      }
      return Promise.reject(err);
    }

    // Only fire toasts for 5xx server errors + status 0 (network / CORS /
    // timeout). 4xx are usually expected (404 = gone, 409 = conflict) and
    // callers surface those inline.
    const isServerError = status >= 500 && status < 600;
    const isNetworkOrTimeout = status === 0;
    const optedOut = config.toast === false;

    if (!optedOut && (isServerError || isNetworkOrTimeout)) {
      const override = typeof config.toast === 'object' ? config.toast : undefined;
      const description =
        override?.description ??
        (isNetworkOrTimeout
          ? "Aurora couldn't reach the server. Check your connection or try again."
          : "Aurora hit a server error. This is usually transient \u2014 try again in a moment.");
      const title = override?.title ?? (isNetworkOrTimeout ? 'Network trouble' : 'Server error');

      if (shouldRaiseToast(description)) {
        toast({ title, description, variant: 'destructive', duration: 6000 });
      }
    }

    return Promise.reject(err);
  },
);

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly body?: unknown,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export function unwrapError(err: unknown): ApiError {
  if (err instanceof AxiosError) {
    const status = err.response?.status ?? 0;
    const body = err.response?.data;
    const msg =
      (typeof body === 'object' && body && 'message' in body
        ? String((body as { message: unknown }).message)
        : err.message) || 'Request failed';
    return new ApiError(status, msg, body);
  }
  return new ApiError(0, err instanceof Error ? err.message : 'Unknown error');
}
