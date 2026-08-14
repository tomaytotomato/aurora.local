package com.tomaytotomato.aurora.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Live tunnel status snapshot — the {@code VpnStatus} shape from {@code
 * openapi.yaml}. {@code interface} is a Java keyword, hence the {@code
 * iface} field name with an explicit {@link JsonProperty} to keep the
 * wire shape exactly as specified.
 *
 * <p>{@code reachable} is honestly {@code null} until Aurora actually
 * probes external reachability — see {@code VpnService} for why that
 * probe is not implemented yet. Reporting "not checked" is preferred over
 * a value nobody verified.
 */
public record VpnStatus(
    String runState,
    @JsonProperty("interface") String iface,
    Integer listenPort,
    String endpoint,
    int peersTotal,
    int peersOnline,
    Boolean reachable,
    String lastCheckedAt,
    String generatedAt
) {

  public static final String RUNNING = "running";
  public static final String STOPPED = "stopped";
  public static final String DEGRADED = "degraded";
  public static final String NOT_CONFIGURED = "not-configured";
}
