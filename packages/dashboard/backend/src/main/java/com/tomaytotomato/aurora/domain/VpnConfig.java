package com.tomaytotomato.aurora.domain;

/**
 * Aurora's own inbound WireGuard server configuration — the {@code
 * VpnConfig} shape from {@code openapi.yaml}. NOT {@code packages/privacy}'s
 * Gluetun sidecar (see {@code docs/SPLIT_TUNNEL.md}); this is the box's own
 * WireGuard <em>server</em> for remote access into the LAN.
 *
 * <p>Deliberately has no {@code serverPrivateKey} field. The private key
 * exists (it has to, to eventually write a real {@code wg0.conf}) but it
 * lives only in {@code VpnConfigRow} / the {@code vpn_config} table; this
 * record is what every controller method returns, and a field that does
 * not exist cannot be serialised into a response by accident.
 */
public record VpnConfig(
    String endpointHost,
    int listenPort,
    String dns,
    String serverAddress,
    int mtu,
    String serverPublicKey
) {}
