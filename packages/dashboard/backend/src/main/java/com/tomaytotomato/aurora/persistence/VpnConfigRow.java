package com.tomaytotomato.aurora.persistence;

/**
 * Full row from {@code vpn_config}, including the server's private key.
 *
 * <p>Deliberately not the same type as {@code domain.VpnConfig} — that
 * record has no private-key field at all, so nothing that only has a
 * {@code VpnConfig} in hand can leak one. Only {@link VpnConfigRepo} and
 * {@code VpnService} ever see this row shape.
 */
public record VpnConfigRow(
    String endpointHost,
    int listenPort,
    String dns,
    String serverAddress,
    int mtu,
    String serverPrivateKey,
    String serverPublicKey
) {}
