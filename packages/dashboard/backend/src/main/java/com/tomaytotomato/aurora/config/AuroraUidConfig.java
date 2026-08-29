package com.tomaytotomato.aurora.config;

import com.tomaytotomato.aurora.services.NetworkMountService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The uid Aurora and its sibling containers run as.
 *
 * <p>Network mounts need it: without {@code uid=}/{@code gid=} on a CIFS
 * mount every file appears owned by root inside the container, and an app
 * running as the aurora user cannot read the media it was pointed at.
 *
 * <p>Read from the environment compose already sets ({@code AURORA_UID}),
 * defaulting to 1000 — the value {@code packages/dashboard/compose.yml}
 * itself defaults to, so the two cannot disagree.
 */
@Configuration
public class AuroraUidConfig {

  @Bean
  public NetworkMountService.AuroraUid auroraUid() {
    String env = System.getenv("AURORA_UID");
    int parsed = 1000;
    if (env != null && !env.isBlank()) {
      try {
        parsed = Integer.parseInt(env.trim());
      } catch (NumberFormatException ignored) {
        // Keep the default rather than fail startup over a malformed env.
      }
    }
    final int value = parsed;
    return () -> value;
  }
}
