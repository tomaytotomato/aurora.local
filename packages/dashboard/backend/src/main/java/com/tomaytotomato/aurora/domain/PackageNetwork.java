package com.tomaytotomato.aurora.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * How one package's traffic leaves the box (see docs/SPLIT_TUNNEL.md).
 * Mirrors openapi.yaml's {@code PackageNetwork} schema.
 *
 * <p>The per-app toggle itself isn't built yet, so {@link #locked} is
 * always {@code true}; {@link #lockedReason} says why.
 *
 * <p>{@code package} is a Java keyword, hence {@code pkg} + {@link JsonProperty}.
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
