package com.tomaytotomato.aurora.domain;

/**
 * A WireGuard peer (a phone, a laptop) allowed to dial into this box's
 * inbound tunnel. Carries the peer's <em>public</em> key only — see
 * {@link VpnPeerSecret} for why the private key is never stored.
 */
public record VpnPeer(
    String id,
    String name,
    String publicKey,
    String allowedIps,
    boolean killSwitch,
    boolean enabled,
    String lastHandshakeAt,
    long rxBytes,
    long txBytes,
    String createdAt
) {}
