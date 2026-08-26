package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.domain.VpnConfig;
import com.tomaytotomato.aurora.domain.VpnPeer;
import com.tomaytotomato.aurora.domain.VpnPeerSecret;
import com.tomaytotomato.aurora.domain.VpnStatus;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import com.tomaytotomato.aurora.persistence.VpnConfigRepo;
import com.tomaytotomato.aurora.persistence.VpnConfigRow;
import com.tomaytotomato.aurora.persistence.VpnPeerRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Aurora's own inbound WireGuard server: config, keypair, peers, live
 * status. NOT {@code packages/privacy}'s Gluetun sidecar — see
 * {@code docs/SPLIT_TUNNEL.md} and {@code docs/VPN_PAGE_DESIGN.md} for
 * why those are two unrelated things that both happen to be called VPN.
 *
 * <p><b>Private keys never leave this class in a form that can be
 * logged or returned twice.</b> The server's own private key lives only
 * in {@link VpnConfigRow} — every method here that returns something to
 * a controller returns a {@link VpnConfig}, which has no field for it.
 * A peer's private key exists only for the duration of {@link
 * #addPeer}: generated, put into the one-time {@link VpnPeerSecret}
 * response, and never written to the database at all (see
 * {@code V4__vpn.sql} — {@code vpn_peer} has no private-key column).
 * Nothing in this class ever passes a private key to a logger.
 *
 * <p>Status reads try to refresh peer traffic/handshake data from
 * {@code wg show <iface> dump} via {@link CommandRunner}. On a box with
 * no {@code wg} binary (every dev/test box) that command fails to start,
 * which this class treats as an honest "gateway down" rather than an
 * error — {@link WireGuardKeys} generation and DB reads work fine
 * either way, so a box with no WireGuard installed yet still gets a
 * correct not-configured/stopped status rather than a 500.
 */
@Service
public class VpnService {

  private static final Logger log = LoggerFactory.getLogger(VpnService.class);

  /** Matches the design doc's own wire-format default and worked example. */
  static final String DEFAULT_IFACE = "wg0";
  private static final Duration ONLINE_WINDOW = Duration.ofMinutes(3);

  private final VpnConfigRepo configRepo;
  private final VpnPeerRepo peerRepo;
  private final CommandRunner commands;
  private final SystemService system;
  private final AuditEventRepo audit;

  public VpnService(VpnConfigRepo configRepo, VpnPeerRepo peerRepo, CommandRunner commands,
                    SystemService system, AuditEventRepo audit) {
    this.configRepo = configRepo;
    this.peerRepo = peerRepo;
    this.commands = commands;
    this.system = system;
    this.audit = audit;
  }

  // ------------------------------------------------------------------
  // Config
  // ------------------------------------------------------------------

  public Optional<VpnConfig> getConfig() {
    return configRepo.find().map(VpnService::toApiConfig);
  }

  /**
   * First-run: generate a server keypair and sensible defaults. Throws
   * {@link IllegalStateException} if a config already exists — the
   * controller maps that to 409, matching the spec.
   */
  public VpnConfig initConfig() {
    if (configRepo.exists()) {
      throw new IllegalStateException("vpn is already configured");
    }
    var keys = WireGuardKeys.generate();
    var row = new VpnConfigRow(
        "", 51820, defaultDns(), "10.66.66.1/24", 1420,
        keys.privateKeyBase64(), keys.publicKeyBase64());
    configRepo.insert(row);
    log.info("vpn: server configuration generated (listenPort={})", row.listenPort());
    audit.record(null, "vpn.config.init", DEFAULT_IFACE,
        "{\"listen_port\":" + row.listenPort() + "}");
    return toApiConfig(row);
  }

  /**
   * Discard the server configuration and every peer, returning the box to
   * its not-configured state. Throws {@link IllegalStateException} when
   * there is nothing to remove; the controller maps that to 404.
   *
   * <p>The inverse of {@link #initConfig()}, which for a long time had
   * none: a single click on the setup screen generated a keypair and there
   * was no way back to that screen short of editing the database.
   *
   * <p>Peers go with it deliberately. Each one's issued {@code .conf}
   * authenticates against the server key being discarded here, so keeping
   * the rows would leave a list of devices that look valid and cannot
   * connect to anything.
   *
   * @return how many peers were deleted alongside the config
   */
  public int removeConfig() {
    if (!configRepo.exists()) {
      throw new IllegalStateException("vpn is not configured");
    }
    int peersDeleted = peerRepo.deleteAll();
    configRepo.delete();
    log.info("vpn: server configuration removed ({} peer(s) deleted)", peersDeleted);
    audit.record(null, "vpn.config.remove", DEFAULT_IFACE,
        "{\"peers_deleted\":" + peersDeleted + "}");
    return peersDeleted;
  }

  /**
   * Partial update of the editable fields. Creates the row on the fly if
   * {@code init} was never called — the spec documents no error response
   * for this endpoint, and refusing to save a value the operator just
   * typed would be a worse experience than lazily creating an unkeyed
   * row (serverPublicKey stays null until init/rotate actually run).
   */
  public VpnConfig updateConfig(VpnConfig req) {
    if (!configRepo.exists()) {
      configRepo.insert(new VpnConfigRow(
          req.endpointHost(), req.listenPort(), req.dns(), req.serverAddress(), req.mtu(),
          null, null));
    } else {
      configRepo.update(req.endpointHost(), req.listenPort(), req.dns(), req.serverAddress(), req.mtu());
    }
    return configRepo.find().map(VpnService::toApiConfig).orElseThrow();
  }

  /**
   * Regenerate the server keypair. Every peer's already-issued {@code
   * .conf} embeds the old public key, so this quietly breaks them until
   * re-downloaded — the spec calls this out as destructive by design.
   */
  public VpnConfig rotateKey() {
    if (!configRepo.exists()) {
      throw new IllegalStateException("vpn is not configured yet");
    }
    var keys = WireGuardKeys.generate();
    configRepo.updateKeys(keys.privateKeyBase64(), keys.publicKeyBase64());
    log.info("vpn: server keypair rotated");
    audit.record(null, "vpn.server.rotate-key", DEFAULT_IFACE,
        "{\"peers_invalidated\":" + peerRepo.count() + "}");
    return configRepo.find().map(VpnService::toApiConfig).orElseThrow();
  }

  // ------------------------------------------------------------------
  // Status
  // ------------------------------------------------------------------

  public VpnStatus status() {
    String now = Instant.now().toString();
    Optional<VpnConfigRow> config = configRepo.find();
    if (config.isEmpty()) {
      return new VpnStatus(VpnStatus.NOT_CONFIGURED, null, null, null, 0, 0, null, null, now);
    }

    VpnConfigRow cfg = config.get();
    boolean gatewayUp = refreshLiveDataFromGateway();
    long peersTotal = peerRepo.count();
    long peersOnline = gatewayUp ? countOnlinePeers() : 0;

    String endpoint = cfg.endpointHost() == null || cfg.endpointHost().isBlank()
        ? null : cfg.endpointHost() + ":" + cfg.listenPort();

    return new VpnStatus(
        gatewayUp ? VpnStatus.RUNNING : VpnStatus.STOPPED,
        DEFAULT_IFACE,
        cfg.listenPort(),
        endpoint,
        (int) peersTotal,
        (int) peersOnline,
        // Not implemented in this iteration: Aurora has no real external
        // probe to ask "can a phone actually reach this box". Reporting
        // null (not checked) is the honest answer — see the class
        // javadoc.
        null,
        now,
        now);
  }

  private long countOnlinePeers() {
    Instant cutoff = Instant.now().minus(ONLINE_WINDOW);
    return peerRepo.findAll().stream()
        .filter(p -> isOnline(p.lastHandshakeAt(), cutoff))
        .count();
  }

  private static boolean isOnline(String lastHandshakeAt, Instant cutoff) {
    if (lastHandshakeAt == null || lastHandshakeAt.isBlank()) return false;
    try {
      return Instant.parse(lastHandshakeAt).isAfter(cutoff);
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  /**
   * {@code wg show <iface> dump}: one interface line, then one line per
   * peer (tab-separated: public key, preshared key, endpoint,
   * allowed-ips, latest-handshake epoch, rx, tx, keepalive). Merges
   * whatever it finds into {@link VpnPeerRepo} by public key and reports
   * whether the gateway answered at all.
   */
  private boolean refreshLiveDataFromGateway() {
    var result = commands.run(List.of("wg", "show", DEFAULT_IFACE, "dump"));
    if (!result.ok()) {
      log.debug("vpn: wg show {} dump did not succeed (exit={}, timedOut={}) — reporting gateway down",
          DEFAULT_IFACE, result.exitCode(), result.timedOut());
      return false;
    }

    List<VpnPeer> known = peerRepo.findAll();
    for (String line : result.lines()) {
      String[] parts = line.split("\t");
      // First field is either the interface's own private key (interface
      // line) or a peer's public key (peer line). We only act on lines
      // that match a peer we already know about.
      if (parts.length < 7) continue;
      String publicKey = parts[0];
      VpnPeer peer = known.stream().filter(p -> p.publicKey().equals(publicKey)).findFirst().orElse(null);
      if (peer == null) continue;

      long handshakeEpoch = parseLongOrZero(parts[4]);
      long rx = parseLongOrZero(parts[5]);
      long tx = parseLongOrZero(parts[6]);
      String handshakeIso = handshakeEpoch <= 0 ? null : Instant.ofEpochSecond(handshakeEpoch).toString();
      peerRepo.updateLiveStats(peer.id(), handshakeIso, rx, tx);
    }
    return true;
  }

  private static long parseLongOrZero(String s) {
    try {
      return Long.parseLong(s.trim());
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  // ------------------------------------------------------------------
  // Peers
  // ------------------------------------------------------------------

  public List<VpnPeer> listPeers() {
    return peerRepo.findAll();
  }

  public Optional<VpnPeer> findPeer(String id) {
    return peerRepo.findById(id);
  }

  /**
   * Add a peer. Requires a server config to already exist — a peer's
   * {@code .conf} needs the server's public key and endpoint, neither of
   * which exist before {@code POST /vpn/config/init} has run.
   */
  public VpnPeerSecret addPeer(String name, String allowedIpsMode) {
    VpnConfigRow cfg = configRepo.find()
        .orElseThrow(() -> new IllegalStateException("vpn is not configured yet"));
    boolean fullTunnel = "full".equals(allowedIpsMode);

    var keys = WireGuardKeys.generate();
    long existingPeers = peerRepo.count();
    String tunnelAddress = nextPeerAddress(cfg.serverAddress(), existingPeers);
    String allowedIps = fullTunnel
        ? "0.0.0.0/0, ::/0"
        : lanCidrGuess() + ", " + tunnelAddress + "/32";

    VpnPeer peer = new VpnPeer(
        UUID.randomUUID().toString(), name, keys.publicKeyBase64(), allowedIps,
        fullTunnel, true, null, 0L, 0L, Instant.now().toString());
    peerRepo.insert(peer);
    log.info("vpn: peer added id={} name={} killSwitch={}", peer.id(), peer.name(), peer.killSwitch());

    String confText = buildPeerConf(cfg, keys.privateKeyBase64(), tunnelAddress, peer.allowedIps());
    String qrPngBase64 = QrCodes.pngBase64(confText);
    return new VpnPeerSecret(peer, keys.privateKeyBase64(), qrPngBase64, confText);
  }

  public void deletePeer(String id) {
    if (peerRepo.deleteById(id) == 0) {
      throw new java.util.NoSuchElementException("no such peer: " + id);
    }
    log.info("vpn: peer removed id={}", id);
  }

  public VpnPeer togglePeer(String id) {
    VpnPeer existing = peerRepo.findById(id)
        .orElseThrow(() -> new java.util.NoSuchElementException("no such peer: " + id));
    peerRepo.setEnabled(id, !existing.enabled());
    log.info("vpn: peer {} id={}", existing.enabled() ? "suspended" : "resumed", id);
    return peerRepo.findById(id).orElseThrow();
  }

  // ------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------

  private String defaultDns() {
    // VPN_PAGE_DESIGN.md §3.4: default to the Privacy package's AdGuard
    // LAN IP if that package is enabled, else a public resolver. Cross-
    // package lookups for "is privacy enabled and what's its IP" are out
    // of scope for tonight (no packages/vpn bundle exists yet either —
    // see VPN_PAGE_DESIGN.md §7); 1.1.1.1 is the documented fallback.
    return "1.1.1.1";
  }

  /**
   * Best-effort LAN subnet for a split-tunnel peer's allowed-ips, guessed
   * as this box's detected LAN IP's /24. A heuristic, not a router
   * lookup — most home networks are a single flat /24, but this will be
   * wrong for anyone running a more elaborate VLAN setup. Flagged in the
   * PR notes as an assumption to revisit.
   */
  private String lanCidrGuess() {
    String lanIp = system.lanIp();
    if (lanIp == null) return "10.0.0.0/8";
    int lastDot = lanIp.lastIndexOf('.');
    if (lastDot < 0) return "10.0.0.0/8";
    return lanIp.substring(0, lastDot) + ".0/24";
  }

  /**
   * Next free tunnel address inside {@code serverAddress}'s /24, skipping
   * .0/.1 (network + server). Peer N (0-indexed) gets host offset N+2.
   */
  static String nextPeerAddress(String serverAddress, long existingPeerCount) {
    String host = serverAddress.split("/")[0];
    int lastDot = host.lastIndexOf('.');
    String base = host.substring(0, lastDot);
    long offset = existingPeerCount + 2;
    return base + "." + offset;
  }

  private static String buildPeerConf(VpnConfigRow cfg, String peerPrivateKey, String tunnelAddress,
                                      String allowedIps) {
    String endpoint = (cfg.endpointHost() == null || cfg.endpointHost().isBlank())
        ? "# no endpoint host configured yet — set one on the Overview tab"
        : cfg.endpointHost() + ":" + cfg.listenPort();
    return """
        [Interface]
        PrivateKey = %s
        Address = %s/32
        DNS = %s

        [Peer]
        PublicKey = %s
        Endpoint = %s
        AllowedIPs = %s
        PersistentKeepalive = 25
        """.formatted(peerPrivateKey, tunnelAddress, cfg.dns(), cfg.serverPublicKey(), endpoint, allowedIps);
  }

  private static VpnConfig toApiConfig(VpnConfigRow row) {
    return new VpnConfig(row.endpointHost(), row.listenPort(), row.dns(), row.serverAddress(),
        row.mtu(), row.serverPublicKey());
  }
}
