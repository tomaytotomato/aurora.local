import { http, HttpResponse } from 'msw';

import type { ProxyConflict, ProxyRoute } from '@/api/proxy';
import { RESERVED_SUBDOMAINS } from '@/api/proxy';

import { state } from '../state';
import { initialTargets, snippetFor } from '../fixtures/proxy';
import { mdnsAliases } from '../fixtures/observability';
import { noContent, nowIso } from './shared';

const DOMAIN = 'aurora.local';

function conflictsFor(subdomain: string, target: string): ProxyConflict[] {
  const out: ProxyConflict[] = [];
  const vhost = `${subdomain}.${DOMAIN}`;

  if (RESERVED_SUBDOMAINS.includes(subdomain)) {
    out.push({
      kind: 'reserved',
      message: `"${subdomain}" is reserved by Aurora itself.`,
      advisory: false,
    });
  }

  const taken = state.proxy.routes.find((r) => r.vhost === vhost);
  if (taken) {
    out.push({
      kind: 'vhost-taken',
      message: taken.managed
        ? `${vhost} already belongs to the ${taken.package} package.`
        : `${vhost} is already pointing at ${taken.target}.`,
      advisory: false,
    });
  }

  // An mDNS alias with the same name is a warning rather than a blocker:
  // it will resolve, it just may not resolve to what you expect.
  if (mdnsAliases.aliases?.some((a) => a.alias === vhost) && !taken) {
    out.push({
      kind: 'mdns-alias',
      message: `${vhost} is already published on the LAN by mDNS. It will still work, but two things now answer to that name.`,
      advisory: true,
    });
  }

  const container = target.split(':')[0];
  if (container && !initialTargets().some((t) => t.container === container)) {
    out.push({
      kind: 'target-unreachable',
      message: `Aurora can't see a container called ${container} on the network right now. The route will be written anyway and will start working when it appears.`,
      advisory: true,
    });
  }

  return out;
}

export const proxyHandlers = [
  http.get('/api/proxy/routes', () => HttpResponse.json(state.proxy.routes)),

  http.get('/api/proxy/targets', () => HttpResponse.json(initialTargets())),

  http.post('/api/proxy/preview', async ({ request }) => {
    const { subdomain, target } = (await request.json()) as { subdomain: string; target: string };
    const vhost = `${subdomain}.${DOMAIN}`;
    return HttpResponse.json({
      vhost,
      snippet: snippetFor(vhost, target),
      conflicts: conflictsFor(subdomain, target),
    });
  }),

  http.post('/api/proxy/routes', async ({ request }) => {
    const { subdomain, target } = (await request.json()) as { subdomain: string; target: string };
    const blocking = conflictsFor(subdomain, target).filter((c) => !c.advisory);
    if (blocking.length) {
      return HttpResponse.json({ message: blocking[0].message }, { status: 409 });
    }
    const route: ProxyRoute = {
      id: 'route-' + Math.random().toString(36).slice(2, 8),
      subdomain,
      vhost: `${subdomain}.${DOMAIN}`,
      target,
      managed: false,
      package: null,
      createdAt: nowIso(),
    };
    state.proxy.routes = [...state.proxy.routes, route];
    return HttpResponse.json(route, { status: 201 });
  }),

  http.delete('/api/proxy/routes/:id', ({ params }) => {
    const route = state.proxy.routes.find((r) => r.id === String(params.id));
    if (route?.managed) {
      return HttpResponse.json(
        { message: 'That route comes from a package manifest. Remove the app instead.' },
        { status: 409 },
      );
    }
    state.proxy.routes = state.proxy.routes.filter((r) => r.id !== String(params.id));
    return noContent();
  }),
];
