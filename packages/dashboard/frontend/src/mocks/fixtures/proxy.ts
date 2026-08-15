// Reverse-proxy routes and the containers worth pointing at.
//
// The managed rows come from package manifests and are read-only. One
// hand-added route is present so the delete path is reachable.

import type { ProxyRoute, ProxyTarget } from '@/api/proxy';

export function initialRoutes(): ProxyRoute[] {
  const managed = (subdomain: string, target: string, pkg: string): ProxyRoute => ({
    id: `route-${subdomain}`,
    subdomain,
    vhost: `${subdomain}.aurora.local`,
    target,
    managed: true,
    package: pkg,
    createdAt: null,
  });

  return [
    managed('admin', 'aurora-dashboard:8090', 'core'),
    managed('auth', 'authelia:9091', 'identity'),
    managed('photos', 'immich-server:2283', 'photos'),
    managed('notes', 'silverbullet:3000', 'notes'),
    managed('paperless', 'paperless:8000', 'documents'),
    managed('grafana', 'grafana:3000', 'monitoring'),
    managed('jellyfin', 'jellyfin:8096', 'jellyfin'),
    {
      id: 'route-books',
      subdomain: 'books',
      vhost: 'books.aurora.local',
      target: 'calibre-web:8083',
      managed: false,
      package: null,
      createdAt: '2026-07-14T19:22:00Z',
    },
  ];
}

export function initialTargets(): ProxyTarget[] {
  return [
    { container: 'calibre-web', ports: [8083], package: null },
    { container: 'immich-server', ports: [2283], package: 'photos' },
    { container: 'silverbullet', ports: [3000], package: 'notes' },
    { container: 'memos', ports: [5230], package: 'notes' },
    { container: 'jellyfin', ports: [8096, 8920], package: 'jellyfin' },
    { container: 'grafana', ports: [3000], package: 'monitoring' },
    { container: 'prometheus', ports: [9090], package: 'monitoring' },
    { container: 'ollama', ports: [11434], package: 'ai' },
    { container: 'open-webui', ports: [8080], package: 'ai' },
    { container: 'kopia', ports: [51515], package: 'backup' },
  ];
}

/** The fragment Aurora would append to caddy.snippet, as Caddy reads it. */
export function snippetFor(vhost: string, target: string): string {
  return [
    `${vhost} {`,
    `  import aurora_defaults`,
    `  reverse_proxy ${target}`,
    `}`,
  ].join('\n');
}
