package com.tomaytotomato.aurora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All Aurora runtime paths + docker config, bound from application.yml.
 */
@ConfigurationProperties(prefix = "aurora")
public record AuroraProperties(
    String repoPath,
    String hostProcPath,
    Docker docker
) {
  public record Docker(String host) {}
}
