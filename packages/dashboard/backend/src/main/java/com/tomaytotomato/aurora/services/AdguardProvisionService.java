package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.domain.AdminUser;
import com.tomaytotomato.aurora.persistence.AdminUserRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Writes AdGuard Home's config before AdGuard ever starts, so the DNS story
 * the wizard sold actually exists on the finished box.
 *
 * <p><b>What was wrong.</b> Choosing "AdGuard on this box" promised two
 * things: AdGuard installed, and {@code *.<domain>} resolving to this box.
 * Installing the package delivered a container that boots into its own setup
 * wizard on :3000 with an empty {@code conf/} directory — no admin user, no
 * upstreams, and, decisively, <em>no DNS server listening at all</em>
 * ({@code dig @<lan-ip> anything.aurora.local} → connection refused). The
 * repo's answer was {@code scripts/seed-adguard.sh}, which only works after a
 * human has completed that wizard by hand. So the box's chosen DNS story
 * silently did not exist, and pointing a router at it would have taken the
 * whole house offline.
 *
 * <p><b>What this does.</b> The same trick core already uses for Stalwart and
 * Authelia: render the service's config from what Aurora already knows, before
 * first start. The admin user is the Aurora admin, and the password is
 * literally the same password — AdGuard stores a bcrypt hash and so does
 * Aurora, at the same cost, so the stored hash is copied across rather than
 * inventing a second credential the owner would have to be told about.
 *
 * <p>Rewrites cover both {@code <domain>} and {@code *.<domain>} pointing at
 * this box's LAN address, which is what makes {@code jellyfin.aurora.local}
 * resolve on the devices that cannot do multi-label mDNS (every Linux and
 * Android client — see the worksheet's C1).
 *
 * <p><b>Never overwrites.</b> If {@code AdGuardHome.yaml} already exists, this
 * does nothing: after first boot the file is AdGuard's own, with the owner's
 * blocklists and rules in it, and clobbering that would be destroying user
 * data to fix a first-run problem.
 */
@Service
public class AdguardProvisionService {

  private static final Logger log = LoggerFactory.getLogger(AdguardProvisionService.class);

  /** Relative to the repo root; matches packages/privacy/compose.yml's bind mount. */
  static final String CONF_RELATIVE = "data/adguard/conf/AdGuardHome.yaml";

  private final AuroraProperties props;
  private final AdminUserRepo users;
  private final SystemService system;
  private final StateFileService stateFiles;

  public AdguardProvisionService(AuroraProperties props, AdminUserRepo users,
                                 SystemService system, StateFileService stateFiles) {
    this.props = props;
    this.users = users;
    this.system = system;
    this.stateFiles = stateFiles;
  }

  /**
   * Heal a box that installed AdGuard before this existed (or whose config
   * was deleted while the container was down): if the package is enabled and
   * there is no config, write one. AdGuard reads it at start, so this takes
   * effect on its next restart rather than immediately — which is why the log
   * line says so.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    Thread.ofVirtual().name("adguard-provision-startup").start(() -> {
      try {
        var enabled = stateFiles.readState().enabled();
        if (enabled == null || !enabled.contains("privacy")) return;
        if (provisionIfAbsent()) {
          log.info("adguard provision: config written for an already-installed "
              + "AdGuard; it takes effect the next time that container starts");
        }
      } catch (Exception e) {
        log.debug("adguard provision: startup pass skipped: {}", e.toString());
      }
    });
  }

  /**
   * Idempotent. Returns true when a config was written by this call.
   *
   * <p>Never throws: a box that cannot write this file should still install
   * AdGuard (the owner can finish its own wizard), so failures are logged and
   * swallowed the same way the Authelia projection handles them.
   */
  public boolean provisionIfAbsent() {
    Path conf = Path.of(props.repoPath()).resolve(CONF_RELATIVE);
    try {
      if (Files.exists(conf)) {
        log.debug("adguard provision: {} already exists, leaving it alone", conf);
        return false;
      }

      List<AdminUser> all = users.findAll();
      if (all.isEmpty()) {
        // Before the wizard's admin step. Writing a config with no user
        // would lock the owner out of AdGuard entirely, which is worse
        // than its setup wizard.
        log.info("adguard provision: no admin user yet, deferring");
        return false;
      }
      AdminUser admin = all.get(0);

      String lanIp = system.lanIp();
      if (lanIp == null || lanIp.isBlank()) {
        log.warn("adguard provision: no LAN address detected, deferring "
            + "(rewrites would point nowhere)");
        return false;
      }

      String domain = stateFiles.readState().domain();
      if (domain == null || domain.isBlank()) domain = "aurora.local";

      Files.createDirectories(conf.getParent());
      String yaml = renderYaml(admin.username(), admin.passwordHash(), lanIp, domain);
      atomicWrite(conf, yaml);
      log.info("adguard provision: wrote {} (admin={}, *.{} -> {})",
          conf, admin.username(), domain, lanIp);
      return true;
    } catch (Exception e) {
      log.warn("adguard provision: could not write {}: {}", conf, e.toString());
      return false;
    }
  }

  /**
   * AdGuard Home v0.107 config, deliberately minimal: everything not needed
   * to answer DNS and let the owner in is left at AdGuard's own defaults.
   *
   * <p>{@code bind_hosts: 0.0.0.0} because the box's LAN address can change
   * (DHCP) and a config that stops answering after a lease change is a
   * support call nobody can debug. The firewall is what limits :53 to the
   * LAN, and the host role already does that.
   */
  static String renderYaml(String username, String bcryptHash, String lanIp, String domain) {
    return """
        # Written by Aurora on first install. Safe to edit — Aurora never
        # rewrites this file once it exists.
        #
        # The admin account is your Aurora account, with the same password.
        # The two rewrites below are what make https://<app>.%DOMAIN% work on
        # devices that cannot resolve multi-label .local names, which is every
        # Linux and Android client on your network.
        http:
          address: 0.0.0.0:3000
        users:
          - name: %USER%
            password: %HASH%
        auth_attempts: 5
        block_auth_min: 15
        dns:
          bind_hosts:
            - 0.0.0.0
          port: 53
          upstream_dns:
            - https://dns.quad9.net/dns-query
            - https://dns.cloudflare.com/dns-query
          bootstrap_dns:
            - 9.9.9.10
            - 1.1.1.1
          rewrites:
            - domain: %DOMAIN%
              answer: %LANIP%
            - domain: '*.%DOMAIN%'
              answer: %LANIP%
        filtering:
          protection_enabled: true
          filtering_enabled: true
          rewrites:
            - domain: %DOMAIN%
              answer: %LANIP%
            - domain: '*.%DOMAIN%'
              answer: %LANIP%
        filters:
          - enabled: true
            url: https://adguardteam.github.io/HostlistsRegistry/assets/filter_1.txt
            name: AdGuard DNS filter
            id: 1
        schema_version: 27
        """
        .replace("%USER%", username)
        .replace("%HASH%", bcryptHash)
        .replace("%LANIP%", lanIp)
        .replace("%DOMAIN%", domain);
  }

  private static void atomicWrite(Path target, String content) throws IOException {
    Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
    Files.writeString(tmp, content, StandardCharsets.UTF_8);
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
  }
}
