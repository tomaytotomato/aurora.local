-- Aurora's own inbound VPN server: WireGuard (default) + an
-- optional, deliberately de-emphasised OpenVPN sidecar. NOT
-- packages/privacy's Gluetun tunnel (see docs/SPLIT_TUNNEL.md) — that
-- is an outbound egress gateway for the media stack; this is an
-- inbound server for reaching the LAN from outside. See
-- packages/dashboard/docs/VPN_PAGE_DESIGN.md.

-- Singleton row (id always 1) holding the server's own tunnel config.
-- server_private_key is stored here because Aurora eventually has to
-- write a real wg0.conf, but no controller method or DTO ever reads
-- this column back out to a response — see VpnConfig.java.
CREATE TABLE IF NOT EXISTS vpn_config (
  id                 INTEGER PRIMARY KEY CHECK (id = 1),
  endpoint_host      TEXT    NOT NULL DEFAULT '',
  listen_port        INTEGER NOT NULL DEFAULT 51820,
  dns                TEXT    NOT NULL DEFAULT '1.1.1.1',
  server_address     TEXT    NOT NULL DEFAULT '10.66.66.1/24',
  mtu                INTEGER NOT NULL DEFAULT 1420,
  server_private_key TEXT,
  server_public_key  TEXT,
  created_at         TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at         TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

-- One row per WireGuard peer. No private-key column at all: WireGuard
-- servers only ever need a peer's public key to accept its handshake,
-- so there is nothing sensitive here to protect beyond the public key
-- itself (which is not a secret). The private key is generated,
-- returned once in the POST /vpn/peers response, and never stored.
CREATE TABLE IF NOT EXISTS vpn_peer (
  id                TEXT    PRIMARY KEY,
  name              TEXT    NOT NULL,
  public_key        TEXT    NOT NULL UNIQUE,
  allowed_ips       TEXT    NOT NULL,
  kill_switch       INTEGER NOT NULL DEFAULT 0,
  enabled           INTEGER NOT NULL DEFAULT 1,
  last_handshake_at TEXT,
  rx_bytes          INTEGER NOT NULL DEFAULT 0,
  tx_bytes          INTEGER NOT NULL DEFAULT 0,
  created_at        TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX IF NOT EXISTS idx_vpn_peer_created_at ON vpn_peer(created_at);

-- Singleton row for the secondary OpenVPN server. Off by default.
CREATE TABLE IF NOT EXISTS vpn_openvpn_config (
  id       INTEGER PRIMARY KEY CHECK (id = 1),
  enabled  INTEGER NOT NULL DEFAULT 0,
  port     INTEGER NOT NULL DEFAULT 1194,
  protocol TEXT    NOT NULL DEFAULT 'udp'
);

-- OpenVPN clients get deliberately less of a data model than WireGuard
-- peers too — no allowed-ips/kill-switch choice, no live traffic
-- counters, matching the "less UI" call in VPN_PAGE_DESIGN.md.
CREATE TABLE IF NOT EXISTS vpn_openvpn_client (
  id                 TEXT PRIMARY KEY,
  name               TEXT NOT NULL,
  created_at         TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  last_connected_at  TEXT
);
