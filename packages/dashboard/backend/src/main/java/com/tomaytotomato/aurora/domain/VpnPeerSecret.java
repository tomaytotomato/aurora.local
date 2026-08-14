package com.tomaytotomato.aurora.domain;

/**
 * One-time reveal of a newly created peer's private key, returned only
 * from {@code POST /vpn/peers}.
 *
 * <p>There is deliberately no {@code GET} that can ever produce this
 * shape again. The server does not persist a peer's private key at all —
 * standard WireGuard practice, since the server only ever needs the
 * peer's <em>public</em> key to authenticate an incoming handshake — so
 * "show it to me again" has no data to answer from. Losing it means
 * removing the peer and adding a new one.
 */
public record VpnPeerSecret(
    VpnPeer peer,
    String privateKey,
    String qrPngBase64,
    String confText
) {}
