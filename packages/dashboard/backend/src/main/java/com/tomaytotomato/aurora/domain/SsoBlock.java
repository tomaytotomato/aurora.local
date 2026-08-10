package com.tomaytotomato.aurora.domain;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Phase D iter-6 \u2014 manifest {@code sso:} block for a package.
 *
 * <p>Every {@code packages/&lt;name&gt;/manifest.yml} may declare an
 * {@code sso:} block that describes how Authelia should treat the
 * package's vhosts. Absent block = {@link #DISABLED} (Authelia stays
 * out of the request path; the service manages its own auth or is
 * intentionally open on the LAN).
 *
 * <p>Shape:
 * <pre>
 * sso:
 *   protect: true            # gate the vhost behind Authelia forward-auth
 *   min_role: user           # admin | user | guest (default: user)
 *   trusted_headers: false   # true when service reads Remote-User/Remote-Groups
 * </pre>
 *
 * <p><b>Fields.</b>
 * <ul>
 *   <li>{@code protect} \u2014 when {@code true}, Aurora's Caddy snippet
 *       renderer emits {@code import authelia} inside every
 *       {@code http(s)://.../{DOMAIN}} block for the package (D6).</li>
 *   <li>{@code minRole} \u2014 the least-privileged role that may reach the
 *       vhost. Maps to Authelia's group cascade (see
 *       {@code AutheliaService.groupsFor()}). Callers with a lower
 *       role get bounced back to the Authelia login page with the
 *       standard "insufficient permissions" copy.</li>
 *   <li>{@code trustedHeaders} \u2014 when {@code true}, the service (Grafana,
 *       Paperless, Forgejo) reads {@code Remote-User} + {@code Remote-Groups}
 *       + {@code Remote-Email} headers Caddy injects on Aurora's behalf,
 *       and auto-provisions the account from them. When {@code false},
 *       the service still has its own login page; Authelia just gates
 *       the front door (SilverBullet).</li>
 *   <li>{@code disableEnv} — env-var names in the package's {@code .env}
 *       to blank when SSO is enabled. Neutralises internal auth for
 *       services that would otherwise show a second login page after
 *       Authelia already granted access (SilverBullet's {@code SB_USER},
 *       any service with basic-auth env config). Kept empty for
 *       trusted-header services because they read the {@code Remote-*}
 *       headers and don't need internal auth silenced.</li>
 * </ul>
 */
public record SsoBlock(
    boolean protect,
    Role minRole,
    boolean trustedHeaders,
    List<String> disableEnv
) {

  /** The absent-block default: SSO stays off. */
  public static final SsoBlock DISABLED = new SsoBlock(false, Role.USER, false, List.of());

  /** Sentinel to detect "block was in the yaml but empty". */
  public boolean isDisabled() {
    return !protect;
  }

  /**
   * Parse the {@code sso} sub-map straight from a SnakeYAML load.
   *
   * <p>Unknown keys are silently ignored so a future field addition
   * doesn't break older Aurora versions parsing a newer manifest.
   * Type coercion is lenient:
   * <ul>
   *   <li>{@code protect} \u2014 {@code true} / {@code false} / a string
   *       (case-insensitive). Missing = {@code false}.</li>
   *   <li>{@code min_role} \u2014 parsed via {@link Role#fromWireName}.
   *       Missing or unknown = {@link Role#USER}.</li>
   *   <li>{@code trusted_headers} \u2014 same shape as {@code protect}.
   *       Missing = {@code false}.</li>
   * </ul>
   *
   * <p>Returns {@link #DISABLED} for a {@code null} / non-Map input so
   * callers reading a manifest without the block don't need to
   * null-check.
   */
  public static SsoBlock fromManifest(Object raw) {
    if (!(raw instanceof Map<?, ?> map)) return DISABLED;
    boolean protect = coerceBool(map.get("protect"), false);
    Role minRole = Optional.ofNullable(map.get("min_role"))
        .map(Object::toString)
        .flatMap(Role::fromWireName)
        .orElse(Role.USER);
    boolean trustedHeaders = coerceBool(map.get("trusted_headers"), false);
    List<String> disableEnv = List.of();
    Object rawDisable = map.get("disable_env");
    if (rawDisable instanceof List<?> list) {
      var out = new java.util.ArrayList<String>();
      for (Object o : list) {
        if (o == null) continue;
        String key = o.toString().trim();
        // Match POSIX env var shape so we can't smuggle in shell
        // metacharacters that a future consumer might exec.
        if (key.matches("[A-Za-z_][A-Za-z0-9_]*")) out.add(key);
      }
      disableEnv = List.copyOf(out);
    }
    return new SsoBlock(protect, minRole, trustedHeaders, disableEnv);
  }

  private static boolean coerceBool(Object v, boolean fallback) {
    if (v instanceof Boolean b) return b;
    if (v instanceof String s) {
      String t = s.trim().toLowerCase(Locale.ROOT);
      if (t.equals("true") || t.equals("yes") || t.equals("on") || t.equals("1")) return true;
      if (t.equals("false") || t.equals("no") || t.equals("off") || t.equals("0")) return false;
    }
    if (v instanceof Number n) return n.intValue() != 0;
    return fallback;
  }
}
