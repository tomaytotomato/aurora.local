package com.tomaytotomato.aurora.domain;

/**
 * The secondary, deliberately de-emphasised OpenVPN server config —
 * off by default. See {@code docs/VPN_PAGE_DESIGN.md} §3.4 "Advanced
 * tab" for why this gets less UI than WireGuard.
 */
public record OpenVpnConfig(
    boolean enabled,
    int port,
    String protocol
) {}
