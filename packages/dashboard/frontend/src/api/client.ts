import axios, { type AxiosInstance, AxiosError } from 'axios';

// Thin axios wrapper. Same-origin, cookie-auth. See DASHBOARD_BRIEF §8.
export const http: AxiosInstance = axios.create({
  baseURL: '/api',
  withCredentials: true,
  timeout: 15000,
  headers: {
    'X-Requested-With': 'aurora',
  },
});

// Global 401 hook — bounces user to /login unless already there.
http.interceptors.response.use(
  (r) => r,
  (err: AxiosError) => {
    if (err.response?.status === 401) {
      const path = window.location.pathname;
      if (path !== '/login' && !path.startsWith('/onboarding')) {
        window.location.href = '/login';
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
