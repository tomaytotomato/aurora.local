package com.tomaytotomato.aurora.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * How one package's traffic leaves the box. See {@code docs/SPLIT_TUNNEL.md}
 * for the mechanism (container network-namespace sharing with a gateway
 * such as {@code gluetun}) and {@code openapi.yaml}'s {@code PackageNetwork}
 * schema for the wire contract this mirrors field-for-field.
 *
 * <p>The per-app toggle itself ("move this app onto the VPN gateway from the
 * dashboard") is still {@code docs/SPLIT_TUNNEL.md}'s "Planned" section, not
 * a shipped feature — no compose rewrite, port move, or Caddy vhost update
 * exists yet. {@link #locked} is therefore {@code true} for every package
 * today; {@link #lockedReason} says why rather than the dashboard silently
 * pretending a working switch is available.
 *
 * <p>{@code package} is a Java keyword, hence the {@code pkg} field name
 * with an explicit {@link JsonProperty} — same trick as {@link VpnStatus#iface}.
 */
public record PackageNetwork(
    @JsonProperty("package") String pkg,
    String mode,
    String gateway,
    boolean locked,
    String lockedReason,
    List<String> containers,
    List<Integer> publishedPorts,
    String egressIp,
    String egressCountry,
    boolean gatewayHealthy
) {

  /** Copy shared by every package until the dashboard toggle ships. */
  public static final String NOT_WIRED_UP_YET =
      "Aurora doesn't support changing this from the dashboard yet — see docs/SPLIT_TUNNEL.md. "
          + "Edit the app's compose.yml directly to attach it to a gateway.";
}
