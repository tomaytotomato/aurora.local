import axios, { type AxiosInstance, AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { toast } from '@/composables/useToast';
import { isSafeRedirect } from '@/lib/safeRedirect';

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

/**
 * Where the user is right now, as a relative path.
 *
 * Read from `window.location` rather than the router deliberately.
 * Importing the router here would be a cycle (router -> stores -> api ->
 * client), and under `createWebHistory` vue-router drives navigation
 * through `history.pushState`, which updates `window.location`
 * synchronously — so the two agree.
 *
 * The one case where they can disagree is a 401 arriving while a
 * navigation is already in flight: `pushState` has run for the
 * destination but the destination's own data fetch is what 401'd. In
 * that case we capture the destination, not the origin, which is the
 * more useful of the two anyway — it is the page the user was trying to
 * reach and will want resumed after signing in.
 */
function currentRelativeLocation(): string {
  const { pathname, search, hash } = window.location;
  return `${pathname}${search}${hash}`;
}

http.interceptors.response.use(
  (r) => r,
  (err: AxiosError) => {
    const status = err.response?.status ?? 0;
    const config = (err.config ?? {}) as InternalAxiosRequestConfig;

    if (status === 401) {
      const path = window.location.pathname;
      // Some endpoints treat 401 as a domain answer, not a session
      // signal — the change-password endpoint returns 401 for "wrong
      // current password", and forwarding that to /login would sign
      // the operator out (invalidating the correct session) as
      // punishment for one wrong keystroke. The signal for those
      // endpoints stays inline on the form.
      const requestUrl = (config.url ?? '').toString();
      const isChangePassword =
        requestUrl.endsWith('/auth/password') || requestUrl.includes('/auth/password?');
      if (isChangePassword) {
        return Promise.reject(err);
      }
      if (path !== '/login' && !path.startsWith('/onboarding')) {
        // Carry the current location across the bounce so a session that
        // expires mid-use returns the user to the page they were on.
        // Without this the interceptor navigated to a bare '/login' and
        // the destination was simply lost: being on /apps/roundcube when
        // the session died, signing in, and landing on the dashboard
        // home. Note this is a *separate* defect from LoginView ignoring
        // ?from= — fixing only one of the two leaves the round-trip
        // broken, because there has to be a `from` to honour before
        // honouring it means anything.
        //
        // Same ?from= contract the router guard uses (router/index.ts)
        // and the same allow-list vet. pathname+search+hash is
        // same-origin by construction, but isSafeRedirect() also
        // filters the /login self-loop.
        const here = currentRelativeLocation();
        window.location.href = isSafeRedirect(here)
          ? `/login?from=${encodeURIComponent(here)}`
          : '/login';
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
