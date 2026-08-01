package com.tomaytotomato.aurora.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All Aurora runtime paths + docker config, bound from application.yml.
 */
@ConfigurationProperties(prefix = "aurora")
public record AuroraProperties(
    String repoPath,
    String hostProcPath,
    /**
     * IPv4 CIDRs to reject when auto-detecting the box's LAN IP. Meant for
     * host-specific noise (VPN interfaces, docker bridges the operator uses)
     * that isn't universally rejectable. Truly universal exclusions (loopback,
     * link-local, multicast, CGNAT) stay hardcoded in SystemService.
     * Defaults cover this box's ProtonVPN range and the two docker bridges
     * that ship out of the box.
     */
    List<String> lanIpExcludedCidrs,
    Docker docker
) {
  public AuroraProperties {
    if (lanIpExcludedCidrs == null) {
      lanIpExcludedCidrs = List.of("10.2.0.0/16", "172.17.0.0/16", "172.18.0.0/16");
    }
  }

  public record Docker(String host) {}
}
