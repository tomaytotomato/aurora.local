// VPN fixtures — Aurora's inbound WireGuard server (see docs/VPN_PAGE_DESIGN.md).
// The peer set covers the states the Peers table needs to render: one
// online, one idle, one full-tunnel with the kill switch on.

import type {
  OpenVpnClient,
  OpenVpnConfig,
  VpnConfig,
  VpnPeer,
} from '@/api/vpn';

// A 1x1 transparent PNG — stands in for the QR image in mock mode. Good
// enough for the <img> to load; not meant to be scannable in dev.
export const PLACEHOLDER_QR_PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

export function initialVpnConfig(): VpnConfig {
  return {
    endpointHost: 'aurora.duckdns.org',
    listenPort: 51820,
    dns: '192.168.1.10', // the Privacy package's AdGuard, so remote devices keep ad-blocking
    serverAddress: '10.66.66.1/24',
    mtu: 1420,
    serverPublicKey: 'kM8Xf2c9F0oQ2mNr7serverpubkeyExampleValue=',
  };
}

export function initialPeers(): VpnPeer[] {
  return [
    {
      id: 'peer-phone',
      name: "Bruce's phone",
      publicKey: 'aZ1p…phone…=',
      allowedIps: '192.168.1.0/24, 10.66.66.2/32',
      killSwitch: false,
      enabled: true,
      lastHandshakeAt: '2026-08-06T08:44:30Z',
      rxBytes: 120 * 1024 * 1024,
      txBytes: 8 * 1024 * 1024,
      createdAt: '2026-08-01T10:00:00Z',
    },
    {
      id: 'peer-laptop',
      name: "Work laptop",
      publicKey: 'bY2q…laptop…=',
      allowedIps: '192.168.1.0/24, 10.66.66.3/32',
      killSwitch: false,
      enabled: true,
      lastHandshakeAt: '2026-08-05T22:10:00Z',
      rxBytes: 2 * 1024 * 1024 * 1024,
      txBytes: 340 * 1024 * 1024,
      createdAt: '2026-08-02T18:30:00Z',
    },
    {
      id: 'peer-travel',
      name: 'Travel tablet (full tunnel)',
      publicKey: 'cX3r…tablet…=',
      allowedIps: '0.0.0.0/0',
      killSwitch: true,
      enabled: false,
      lastHandshakeAt: null,
      rxBytes: 0,
      txBytes: 0,
      createdAt: '2026-08-04T09:15:00Z',
    },
  ];
}

export function initialOpenVpnConfig(): OpenVpnConfig {
  return { enabled: false, port: 1194, protocol: 'udp' };
}

export function initialOpenVpnClients(): OpenVpnClient[] {
  return [];
}

/** A believable WireGuard peer .conf body for the download/reveal flows. */
export function peerConfText(peerName: string, cfg: VpnConfig): string {
  return [
    '[Interface]',
    '# ' + peerName,
    'PrivateKey = <generated-once-shown-once>',
    'Address = 10.66.66.9/32',
    `DNS = ${cfg.dns}`,
    '',
    '[Peer]',
    `PublicKey = ${cfg.serverPublicKey ?? '<server-public-key>'}`,
    'AllowedIPs = 192.168.1.0/24, 10.66.66.0/24',
    `Endpoint = ${cfg.endpointHost}:${cfg.listenPort}`,
    'PersistentKeepalive = 25',
    '',
  ].join('\n');
}
