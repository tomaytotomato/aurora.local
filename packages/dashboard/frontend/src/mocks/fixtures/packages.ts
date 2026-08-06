// Package fixtures. One entry per real aurora package (see repo README).
// `enabled` / `running` are overridden at request time from mock state so
// toggling a package in the UI reflects back; the values here are only
// the initial catalogue shape.

import type {
  EnvVarSpec,
  PackageDetail,
  PackageSummary,
} from '@/api/packages';

interface Seed {
  summary: PackageSummary;
  detail: Omit<PackageDetail, keyof PackageSummary>;
  env: EnvVarSpec[];
}

function envVar(over: Partial<EnvVarSpec> & { key: string }): EnvVarSpec {
  return { secret: false, required: false, ...over };
}

export const packageSeeds: Seed[] = [
  {
    summary: {
      name: 'core',
      title: 'Core',
      category: 'core',
      description: 'Caddy reverse proxy (HTTPS) + Homepage dashboard.',
      enabled: true,
      running: true,
      requires: { start_budget_seconds: 30 },
      ports: [{ host: 443, container: 443 }],
      dependsOn: [],
    },
    detail: {
      readme: '# Core\n\nCaddy terminates TLS and reverse-proxies every other\npackage vhost. Homepage renders the tile grid.',
      vhosts: ['aurora.local'],
      homepageTiles: 0,
    },
    env: [
      envVar({ key: 'CADDY_EMAIL', example: 'you@example.com', comment: 'ACME contact for the internal CA.' }),
    ],
  },
  {
    summary: {
      name: 'privacy',
      title: 'Privacy',
      category: 'privacy',
      description: 'AdGuard Home (LAN DNS) + Gluetun VPN sidecar.',
      enabled: true,
      running: true,
      requires: { start_budget_seconds: 60 },
      ports: [{ host: 53, container: 53 }, { host: 3000, container: 3000 }],
      dependsOn: ['core'],
    },
    detail: {
      readme: '# Privacy\n\nAdGuard Home serves LAN DNS and blocks ads at the\nnetwork edge. Gluetun provides a VPN sidecar for the media stack.',
      vhosts: ['adguard.aurora.local'],
      homepageTiles: 1,
    },
    env: [
      envVar({ key: 'WIREGUARD_PRIVATE_KEY', secret: true, required: true, comment: 'From your VPN provider.' }),
      envVar({ key: 'WIREGUARD_ADDRESSES', example: '10.2.0.2/32', required: true }),
    ],
  },
  {
    summary: {
      name: 'media',
      title: 'Media',
      category: 'media',
      description: 'Sonarr, Radarr, Bazarr, Prowlarr, Seerr, SABnzbd, qBittorrent.',
      enabled: true,
      running: false,
      requires: { start_budget_seconds: 180 },
      ports: [{ host: 8989, container: 8989 }],
      dependsOn: ['core', 'privacy'],
    },
    detail: {
      readme: '# Media\n\nA full *arr automation stack behind the privacy VPN.\nFirst start pulls seven images, so give it a few minutes.',
      vhosts: ['sonarr.aurora.local', 'radarr.aurora.local', 'prowlarr.aurora.local'],
      homepageTiles: 7,
    },
    env: [
      envVar({ key: 'PUID', value: '1000', example: '1000' }),
      envVar({ key: 'PGID', value: '1000', example: '1000' }),
    ],
  },
  {
    summary: {
      name: 'storage',
      title: 'Storage',
      category: 'storage',
      description: 'Samba file sharing + MiniDLNA.',
      enabled: false,
      running: false,
      requires: { start_budget_seconds: 30 },
      ports: [{ host: 445, container: 445 }],
      dependsOn: ['core'],
    },
    detail: {
      readme: '# Storage\n\nSamba shares your data volume on the LAN; MiniDLNA\npublishes media to DLNA clients.',
      vhosts: [],
      homepageTiles: 0,
    },
    env: [
      envVar({ key: 'SAMBA_USER', example: 'aurora', required: true }),
      envVar({ key: 'SAMBA_PASSWORD', secret: true, required: true }),
    ],
  },
  {
    summary: {
      name: 'monitoring',
      title: 'Monitoring',
      category: 'monitoring',
      description: 'Prometheus + Grafana + node_exporter + cAdvisor + Uptime-Kuma.',
      enabled: true,
      running: true,
      requires: { start_budget_seconds: 60 },
      ports: [{ host: 3001, container: 3000 }],
      dependsOn: ['core'],
    },
    detail: {
      readme: '# Monitoring\n\nGrafana dashboards over a Prometheus scrape of the\nhost and every container. Uptime-Kuma probes each vhost.',
      vhosts: ['grafana.aurora.local', 'uptime.aurora.local'],
      homepageTiles: 2,
    },
    env: [
      envVar({ key: 'GRAFANA_ADMIN_PASSWORD', secret: true, required: true }),
    ],
  },
  {
    summary: {
      name: 'photos',
      title: 'Photos',
      category: 'productivity',
      description: 'Immich — self-hosted photo and video backup.',
      enabled: false,
      running: false,
      requires: { start_budget_seconds: 120 },
      ports: [{ host: 2283, container: 2283 }],
      dependsOn: ['core'],
    },
    detail: {
      readme: '# Photos\n\nImmich backs up your phone camera roll with ML search.',
      vhosts: ['photos.aurora.local'],
      homepageTiles: 1,
    },
    env: [
      envVar({ key: 'DB_PASSWORD', secret: true, required: true }),
    ],
  },
  {
    summary: {
      name: 'git',
      title: 'Git',
      category: 'dev',
      description: 'Forgejo + CI runner.',
      enabled: false,
      running: false,
      requires: { start_budget_seconds: 60 },
      ports: [{ host: 3002, container: 3000 }],
      dependsOn: ['core'],
    },
    detail: {
      readme: '# Git\n\nForgejo git forge with a bundled Actions runner.',
      vhosts: ['git.aurora.local'],
      homepageTiles: 1,
    },
    env: [],
  },
  {
    summary: {
      name: 'ai',
      title: 'AI',
      category: 'ai',
      description: 'Ollama + Open-WebUI (CPU default, --gpu opt-in).',
      enabled: false,
      running: false,
      requires: { start_budget_seconds: 90 },
      ports: [{ host: 11434, container: 11434 }],
      dependsOn: ['core'],
    },
    detail: {
      readme: '# AI\n\nRun local LLMs with Ollama behind an Open-WebUI chat.',
      vhosts: ['ai.aurora.local'],
      homepageTiles: 1,
    },
    env: [],
  },
  {
    summary: {
      name: 'identity',
      title: 'Identity',
      category: 'identity',
      description: 'Authelia SSO + 2FA (forward-auth for other packages).',
      enabled: false,
      running: false,
      requires: { start_budget_seconds: 45 },
      ports: [{ host: 9091, container: 9091 }],
      dependsOn: ['core'],
    },
    detail: {
      readme: '# Identity\n\nAuthelia provides single sign-on and 2FA, wired into\nCaddy as forward-auth for any package you protect.',
      vhosts: ['auth.aurora.local'],
      homepageTiles: 1,
    },
    env: [
      envVar({ key: 'AUTHELIA_JWT_SECRET', secret: true, required: true }),
    ],
  },
  {
    summary: {
      name: 'vpn',
      title: 'VPN',
      category: 'network',
      // Off by default, same as the other opt-in packages above — this
      // is an inbound WireGuard server for remote access, the opposite
      // direction of privacy's outbound Gluetun tunnel. See the full
      // config surface at /vpn once enabled.
      description: 'WireGuard remote-access server (OpenVPN available as a secondary option).',
      enabled: false,
      running: false,
      requires: { start_budget_seconds: 30 },
      ports: [{ host: 51820, container: 51820, proto: 'udp' }],
      dependsOn: ['core'],
    },
    detail: {
      readme: '# VPN\n\nA WireGuard server for reaching this LAN from anywhere.\nConfigure endpoint, peers, and the kill switch at /vpn.',
      vhosts: [],
      homepageTiles: 0,
    },
    env: [],
  },
];

export function summaryFor(name: string): PackageSummary | undefined {
  return packageSeeds.find((s) => s.summary.name === name)?.summary;
}

export function detailFor(name: string): PackageDetail | undefined {
  const seed = packageSeeds.find((s) => s.summary.name === name);
  if (!seed) return undefined;
  return { ...seed.summary, ...seed.detail };
}

export function envFor(name: string): EnvVarSpec[] {
  return packageSeeds.find((s) => s.summary.name === name)?.env ?? [];
}
