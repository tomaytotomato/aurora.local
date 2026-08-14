package com.tomaytotomato.aurora.domain;

/** An OpenVPN client profile. No public key material — OpenVPN clients here carry no server-known secret beyond the .ovpn issued once at creation. */
public record OpenVpnClient(
    String id,
    String name,
    String createdAt,
    String lastConnectedAt
) {}
