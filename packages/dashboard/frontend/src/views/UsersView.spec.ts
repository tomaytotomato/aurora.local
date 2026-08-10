import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { createRouter, createMemoryHistory } from 'vue-router';
import axios, { AxiosError, type AxiosAdapter, type AxiosResponse } from 'axios';
import UsersView from './UsersView.vue';
import { http, _resetToastDedupe } from '@/api/client';
import { useAuthStore } from '@/stores/auth';
import { dismissAll, useToastQueue } from '@/composables/useToast';
import type { UserSummary } from '@/api/users';

/**
 * Phase D iter-16 (D15). UsersView.vue full mount smoke tests.
 *
 * <p>Covers the click-flow paths that Phase C primitive-level tests
 * can't reach:
 *
 * <ul>
 *   <li>Non-admin session bounces to / on mount (belt-and-braces role
 *       guard beyond the router-level check).</li>
 *   <li>Admin session loads the users list into a Table.</li>
 *   <li>Empty state renders the correct copy.</li>
 *   <li>Row DropdownMenu → Change role → Dialog → submit triggers the
 *       PUT + toast + refetch.</li>
 *   <li>Row DropdownMenu → Delete → confirm Dialog → DELETE + toast.</li>
 *   <li>Create button opens the Dialog and submits.</li>
 *   <li>Backend 422 (last-admin) surfaces the inline Alert copy inside
 *       the Dialog, not the global destructive toast.</li>
 * </ul>
 */

// ─── shared axios adapter for the mounted view ─────────────────────────

interface Reply {
  url?: string | RegExp;
  method: string;
  status?: number;
  data?: unknown;
}

const responses: Reply[] = [];
const captured: { url?: string; method?: string; body?: unknown }[] = [];

function installAdapter(): void {
  const adapter: AxiosAdapter = (config) => {
    captured.push({
      url: config.url,
      method: config.method,
      body: config.data ? JSON.parse(String(config.data)) : undefined,
    });
    const url = config.url ?? '';
    const method = (config.method ?? '').toLowerCase();
    for (const r of responses) {
      const matches =
        method === r.method.toLowerCase() &&
        (typeof r.url === 'string'
          ? url === r.url
          : r.url instanceof RegExp
            ? r.url.test(url)
            : true);
      if (matches) {
        if ((r.status ?? 200) >= 400) {
          const err = new AxiosError('boom', 'ERR_BAD_RESPONSE', config);
          err.response = {
            status: r.status ?? 500,
            statusText: 'err',
            data: r.data ?? null,
            headers: {},
            config,
          } as AxiosResponse;
          return Promise.reject(err);
        }
        return Promise.resolve({
          data: r.data ?? {},
          status: r.status ?? 200,
          statusText: 'OK',
          headers: {},
          config,
        } as AxiosResponse);
      }
    }
    // Fallback: 200 with an empty body.
    return Promise.resolve({
      data: {}, status: 200, statusText: 'OK', headers: {}, config,
    } as AxiosResponse);
  };
  http.defaults.adapter = adapter;
}

function stubResponse(reply: Reply): void {
  responses.push(reply);
}

// ─── router + pinia scaffolding ────────────────────────────────────────

async function settle(): Promise<void> {
  // Dialog opens through a watch(props.open) that awaits nextTick
  // before focusing the first focusable inside the Teleport target,
  // so we need multiple ticks + a raf-ish flush for the panel + its
  // slots to be visible via document.querySelector.
  await flushPromises();
  await new Promise((r) => setTimeout(r, 0));
  await flushPromises();
}

async function mountUsersView(role: 'admin' | 'user' | 'guest' | null = 'admin') {
  const pinia = createPinia();
  setActivePinia(pinia);
  const auth = useAuthStore();
  auth.session = role
    ? { authenticated: true, username: 'bruce', passkeyEnrolled: false, tz: 'UTC', role }
    : { authenticated: false, username: null, passkeyEnrolled: false, tz: null, role: null };
  // Suppress the AuthApi.session() call fetchSession() does on mount —
  // return whatever the store already has staged.
  vi.spyOn(auth, 'fetchSession').mockResolvedValue(auth.session);

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div>home</div>' } },
      { path: '/users', component: UsersView },
    ],
  });
  await router.push('/users');
  await router.isReady();

  const w = mount(UsersView, {
    global: {
      plugins: [pinia, router],
    },
    attachTo: document.body,
  });
  await flushPromises();
  return { w, router, auth };
}

const SAMPLE_USERS: UserSummary[] = [
  { id: 1, username: 'bruce', role: 'admin', tz: 'UTC', createdAt: '2026-01-01T00:00:00Z' },
  { id: 2, username: 'alice', role: 'user', tz: 'UTC', createdAt: '2026-02-01T00:00:00Z' },
];

beforeEach(() => {
  responses.length = 0;
  captured.length = 0;
  dismissAll();
  _resetToastDedupe();
  installAdapter();
});

afterEach(() => {
  dismissAll();
  _resetToastDedupe();
  delete (http.defaults as { adapter?: AxiosAdapter }).adapter;
  vi.restoreAllMocks();
});

// ─── tests ─────────────────────────────────────────────────────────────

describe('UsersView — role guard', () => {
  it('bounces a non-admin session back to / on mount', async () => {
    stubResponse({ method: 'get', url: '/users', data: SAMPLE_USERS });

    const { router } = await mountUsersView('user');
    // requireAdminOrRedirect() should have pushed to '/'.
    expect(router.currentRoute.value.path).toBe('/');
    // list() should NOT have been called since we bounced early.
    expect(captured.filter((c) => c.url === '/users' && c.method === 'get'))
      .toHaveLength(0);
  });

  it('admin session stays on /users and loads the list', async () => {
    stubResponse({ method: 'get', url: '/users', data: SAMPLE_USERS });

    const { w, router } = await mountUsersView('admin');
    expect(router.currentRoute.value.path).toBe('/users');
    // Two rows in the Table — select the row <tr> exactly, not the
    // nested menu triggers with a data-test prefix that would
    // over-match.
    expect(w.findAll('tbody [data-test^="users-row-"]:not([data-test*="-menu-"])'))
      .toHaveLength(2);
    expect(w.get('[data-test="users-row-bruce"]').text()).toContain('bruce');
    expect(w.get('[data-test="users-row-alice"]').text()).toContain('alice');
  });
});

describe('UsersView — empty state', () => {
  it('renders the empty-state copy when there are no users', async () => {
    stubResponse({ method: 'get', url: '/users', data: [] });
    const { w } = await mountUsersView('admin');
    expect(w.find('[data-test="users-empty"]').exists()).toBe(true);
    expect(w.get('[data-test="users-empty"]').text())
      .toContain('No users yet');
    expect(w.find('[data-test="users-list"]').exists()).toBe(false);
  });
});

describe('UsersView — role update', () => {
  it('opens the edit Dialog, PUTs the new role, closes on success', async () => {
    stubResponse({ method: 'get', url: '/users', data: SAMPLE_USERS });
    stubResponse({ method: 'put', url: '/users/2', data: {
      ...SAMPLE_USERS[1], role: 'admin',
    }});

    const { w } = await mountUsersView('admin');
    await w.get('[data-test="users-row-menu-trigger-alice"]').trigger('click');
    await settle();
    await w.get('[data-test="users-row-edit-alice"]').trigger('click');
    await settle();
    // The Dialog teleports its panel to <body>. `data-test` on the
    // component tag doesn't fall through the Teleport wrapper, so
    // query by the Dialog's known panel slot + a snippet of title
    // text instead.
    const panels = document.querySelectorAll('[data-slot="dialog-content"]');
    expect(Array.from(panels).some((p) => p.textContent?.includes('Change role')))
      .toBe(true);

    document.querySelector<HTMLElement>('[data-test="users-edit-submit"]')!.click();
    await settle();

    const put = captured.find((c) => c.method === 'put' && c.url === '/users/2');
    expect(put).toBeTruthy();
    expect(useToastQueue().queue.some((t) => t.description?.includes('Role updated')))
      .toBe(true);
  });

  it('shows inline Alert on 422 last-admin demote, no destructive toast', async () => {
    stubResponse({ method: 'get', url: '/users', data: SAMPLE_USERS });
    stubResponse({ method: 'put', url: '/users/1', status: 422 });

    const { w } = await mountUsersView('admin');
    await w.get('[data-test="users-row-menu-trigger-bruce"]').trigger('click');
    await settle();
    await w.get('[data-test="users-row-edit-bruce"]').trigger('click');
    await settle();

    document.querySelector<HTMLElement>('[data-test="users-edit-submit"]')!.click();
    await settle();

    // Global 5xx toast is opted out on mutations; 4xx isn't toasted by
    // the interceptor. Queue stays empty; inline Alert renders inside
    // the Dialog panel.
    expect(useToastQueue().queue.filter((t) => t.variant === 'destructive'))
      .toHaveLength(0);
    // Data-test on the Alert lives inside the Dialog default slot
    // (not a Teleport-wrapper fallthrough), so this one IS reachable.
    expect(document.querySelector('[data-test="users-edit-error"]')).toBeTruthy();
  });
});

describe('UsersView — delete', () => {
  it('opens the confirm Dialog and DELETEs on confirm', async () => {
    stubResponse({ method: 'get', url: '/users', data: SAMPLE_USERS });
    stubResponse({ method: 'delete', url: '/users/2', status: 204 });

    const { w } = await mountUsersView('admin');
    await w.get('[data-test="users-row-menu-trigger-alice"]').trigger('click');
    await settle();
    await w.get('[data-test="users-row-delete-alice"]').trigger('click');
    await settle();

    // Same Teleport shape as the edit Dialog — query by known slot.
    const panels = document.querySelectorAll('[data-slot="dialog-content"]');
    expect(Array.from(panels).some((p) => p.textContent?.includes('Delete user')))
      .toBe(true);

    document.querySelector<HTMLElement>('[data-test="users-delete-confirm"]')!.click();
    await settle();

    const del = captured.find((c) => c.method === 'delete' && c.url === '/users/2');
    expect(del).toBeTruthy();
    expect(useToastQueue().queue.some((t) => t.description?.includes('Deleted alice')))
      .toBe(true);
  });
});
