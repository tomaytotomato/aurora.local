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
      description: 'Caddy reverse proxy (HTTPS). Aurora is the dashboard.',
      enabled: true,
      running: true,
      requires: { start_budget_seconds: 30, min_ram_mb: 256, min_disk_gb: 1 },
      ports: [{ host: 443, container: 443 }],
      dependsOn: [],
      sourceUrl: 'https://github.com/caddyserver/caddy',
      homepageUrl: 'https://caddyserver.com',
    },
    detail: {
      readme: '# Core\n\nCaddy terminates TLS and reverse-proxies every other\npackage vhost. Aurora (packages/dashboard) is the dashboard.',
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
      requires: { start_budget_seconds: 60, min_ram_mb: 512, min_disk_gb: 2 },
      ports: [{ host: 53, container: 53 }, { host: 3000, container: 3000 }],
      dependsOn: ['core'],
      sourceUrl: 'https://github.com/AdguardTeam/AdGuardHome',
      homepageUrl: 'https://adguard.com/en/adguard-home/overview.html',
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
      requires: { start_budget_seconds: 180, min_ram_mb: 2048, min_disk_gb: 10 },
      ports: [{ host: 8989, container: 8989 }],
      dependsOn: ['core', 'privacy'],
      sourceUrl: 'https://github.com/Sonarr/Sonarr',
      homepageUrl: 'https://sonarr.tv',
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
      requires: { start_budget_seconds: 30, min_ram_mb: 256, min_disk_gb: 5 },
      ports: [{ host: 445, container: 445 }],
      dependsOn: ['core'],
      sourceUrl: 'https://github.com/samba-team/samba',
      homepageUrl: 'https://www.samba.org',
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
      requires: { start_budget_seconds: 60, min_ram_mb: 1024, min_disk_gb: 5 },
      ports: [{ host: 3001, container: 3000 }],
      dependsOn: ['core'],
      sourceUrl: 'https://github.com/grafana/grafana',
      homepageUrl: 'https://grafana.com',
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
      requires: { start_budget_seconds: 120, min_ram_mb: 2048, min_disk_gb: 20 },
      ports: [{ host: 2283, container: 2283 }],
      dependsOn: ['core'],
      sourceUrl: 'https://github.com/immich-app/immich',
      homepageUrl: 'https://immich.app',
    },
    detail: {
      readme: '# Photos\n\nImmich backs up your phone camera roll with ML search.',
      vhosts: ['photos.aurora.local'],
      homepageTiles: 1,
      backup: {
        paths: ['data/photos/library'],
        before: [
          {
            kind: 'postgres-dump',
            container: 'immich-postgres',
            description: 'Dumps the Immich database so the snapshot restores cleanly',
          },
        ],
      },
    },
    env: [
      envVar({ key: 'DB_PASSWORD', secret: true, required: true }),
    ],
  },
  {
    summary: {
      name: 'ai',
      title: 'AI',
      category: 'ai',
      description: 'Ollama + Open-WebUI (CPU default, --gpu opt-in).',
      enabled: false,
      running: false,
      requires: { start_budget_seconds: 90, min_ram_mb: 8192, min_disk_gb: 10 },
      ports: [{ host: 11434, container: 11434 }],
      dependsOn: ['core'],
      sourceUrl: 'https://github.com/ollama/ollama',
      homepageUrl: 'https://ollama.com',
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
      requires: { start_budget_seconds: 45, min_ram_mb: 256, min_disk_gb: 1 },
      ports: [{ host: 9091, container: 9091 }],
      dependsOn: ['core'],
      sourceUrl: 'https://github.com/authelia/authelia',
      homepageUrl: 'https://www.authelia.com',
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
      requires: { start_budget_seconds: 30, min_ram_mb: 128, min_disk_gb: 1 },
      ports: [{ host: 51820, container: 51820, proto: 'udp' }],
      dependsOn: ['core'],
      sourceUrl: 'https://github.com/WireGuard/wireguard-tools',
      homepageUrl: 'https://www.wireguard.com',
    },
    detail: {
      readme: '# VPN\n\nA WireGuard server for reaching this LAN from anywhere.\nConfigure endpoint, peers, and the kill switch at /vpn.',
      vhosts: [],
      homepageTiles: 0,
    },
    env: [],
  },
  {
    summary: {
      name: 'jellyfin',
      title: 'Media server (Jellyfin)',
      category: 'media',
      description: 'Jellyfin — free media server for the films, TV and music the *arr stack collects.',
      enabled: false,
      running: false,
      requires: { start_budget_seconds: 90, min_ram_mb: 1024, min_disk_gb: 5 },
      ports: [{ host: 8096, container: 8096 }],
      dependsOn: ['core'],
      sourceUrl: 'https://github.com/jellyfin/jellyfin',
      homepageUrl: 'https://jellyfin.org',
    },
    detail: {
      readme: '# Jellyfin\n\nThe media server the *arr stack was missing. Libraries\nmount read-only from your media root. Not behind Authelia.',
      vhosts: ['jellyfin.aurora.local'],
      homepageTiles: 1,
    },
    env: [],
  },
  {
    summary: {
      name: 'notes',
      title: 'Notes (SilverBullet)',
      category: 'productivity',
      description: 'SilverBullet — markdown-native, extensible notes and PKM.',
      enabled: false,
      running: false,
      requires: { start_budget_seconds: 30, min_ram_mb: 256, min_disk_gb: 2 },
      ports: [{ host: 3030, container: 3000 }],
      dependsOn: ['core'],
      sourceUrl: 'https://github.com/silverbulletmd/silverbullet',
      homepageUrl: 'https://silverbullet.md',
    },
    detail: {
      readme: '# Notes\n\nSilverBullet keeps every note as a plain .md file on disk,\nwith a live command palette and a plug ecosystem.',
      vhosts: ['notes.aurora.local'],
      homepageTiles: 1,
    },
    env: [
      envVar({ key: 'SB_USER', example: 'aurora', required: true }),
      envVar({ key: 'SB_PASSWORD', secret: true, required: true }),
    ],
  },
  {
    summary: {
      name: 'memos',
      title: 'Notes (Memos)',
      category: 'productivity',
      description: 'Memos — lightweight, self-hosted memo stream on SQLite.',
      enabled: false,
      running: false,
      requires: { start_budget_seconds: 15, min_ram_mb: 128, min_disk_gb: 1 },
      ports: [{ host: 5230, container: 5230 }],
      dependsOn: ['core'],
      sourceUrl: 'https://github.com/usememos/memos',
      homepageUrl: 'https://www.usememos.com',
    },
    detail: {
      readme: '# Memos\n\nThe lighter alternative to SilverBullet: quick capture,\ntags, full-text search.',
      vhosts: ['memos.aurora.local'],
      homepageTiles: 1,
    },
    env: [],
  },
  {
    summary: {
      name: 'documents',
      title: 'Documents',
      category: 'productivity',
      description: 'Paperless-ngx + Stirling-PDF — scan, OCR and manage documents.',
      enabled: false,
      running: false,
      requires: { start_budget_seconds: 120, min_ram_mb: 1024, min_disk_gb: 10 },
      ports: [{ host: 8010, container: 8000 }],
      dependsOn: ['core'],
      sourceUrl: 'https://github.com/paperless-ngx/paperless-ngx',
      homepageUrl: 'https://docs.paperless-ngx.com',
    },
    detail: {
      readme: '# Documents\n\nPaperless-ngx OCRs and indexes your paperwork; Stirling-PDF\nhandles splits, merges and conversions.',
      vhosts: ['paperless.aurora.local'],
      homepageTiles: 2,
      // Declares paths but no before-action, even though Paperless keeps
      // a Postgres database under this path. This is the gap the backup
      // page warns about, and it is here on purpose.
      backup: {
        paths: ['data/documents'],
        before: [],
      },
    },
    env: [
      envVar({ key: 'PAPERLESS_ADMIN_PASSWORD', secret: true, required: true }),
    ],
  },
  {
    summary: {
      name: 'backup',
      title: 'Backup',
      category: 'storage',
      description: 'Kopia — deduplicated, encrypted backups with a web UI.',
      enabled: false,
      running: false,
      requires: { start_budget_seconds: 30, min_ram_mb: 256, min_disk_gb: 5 },
      ports: [{ host: 51515, container: 51515 }],
      dependsOn: ['core'],
      sourceUrl: 'https://github.com/kopia/kopia',
      homepageUrl: 'https://kopia.io',
    },
    detail: {
      readme: '# Backup\n\nKopia snapshots configs and data to a local or remote\nrepository, deduplicated and encrypted at rest.',
      vhosts: ['backup.aurora.local'],
      homepageTiles: 1,
    },
    env: [
      envVar({ key: 'KOPIA_PASSWORD', secret: true, required: true, comment: 'Repository encryption password. Losing it loses the repo.' }),
    ],
  },
  {
    summary: {
      name: 'dev',
      title: 'Dev',
      category: 'dev',
      description: 'code-server + Postgres 16 + Redis 7.',
      enabled: false,
      running: false,
      requires: { start_budget_seconds: 60, min_ram_mb: 1024, min_disk_gb: 10 },
      ports: [{ host: 8443, container: 8443 }],
      dependsOn: ['core'],
      sourceUrl: 'https://github.com/coder/code-server',
      homepageUrl: 'https://coder.com/docs/code-server',
    },
    detail: {
      readme: '# Dev\n\nA browser IDE (code-server) with a Postgres and Redis\nready for whatever you are building.',
      vhosts: ['code.aurora.local'],
      homepageTiles: 1,
    },
    env: [
      envVar({ key: 'CODE_SERVER_PASSWORD', secret: true, required: true }),
    ],
  },
  {
    summary: {
      name: 'home-automation',
      title: 'Home Automation',
      category: 'home-automation',
      description: 'Home Assistant + Mosquitto + Zigbee2MQTT (--zigbee opt-in).',
      enabled: false,
      running: false,
      requires: { start_budget_seconds: 90, min_ram_mb: 512, min_disk_gb: 5 },
      ports: [{ host: 8123, container: 8123 }],
      dependsOn: ['core'],
      sourceUrl: 'https://github.com/home-assistant/core',
      homepageUrl: 'https://www.home-assistant.io',
    },
    detail: {
      readme: '# Home Automation\n\nHome Assistant with an MQTT broker and optional Zigbee\nbridge for local, cloud-free automation.',
      vhosts: ['home.aurora.local'],
      homepageTiles: 1,
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
