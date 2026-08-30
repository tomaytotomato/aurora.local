package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import com.tomaytotomato.aurora.persistence.SettingsRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Attaches a folder on a network device so this box's apps can use it.
 *
 * <p><b>Docker volumes, not host mounts.</b> Docker's {@code local} driver
 * speaks CIFS natively, so the mount is performed when a container starts
 * and torn down when it stops. Three things follow, and all three are the
 * reason this design was chosen over {@code /etc/fstab} or a systemd unit:
 *
 * <ul>
 *   <li><b>A NAS that is switched off cannot stop the box booting.</b> The
 *       app that wanted the share fails to start, with a message naming the
 *       share; everything else comes up. A bad fstab entry, by contrast,
 *       can leave a machine sitting at a recovery prompt — which on a
 *       headless box in a cupboard means someone plugging in a monitor.</li>
 *   <li><b>No new privilege.</b> Mounting on the host would mean Aurora
 *       gaining the ability to run {@code mount} as root. It already holds
 *       the docker socket; this adds nothing beyond it.</li>
 *   <li><b>It is visible.</b> {@code docker volume ls} shows exactly what
 *       is attached and where it came from, under a name that says so.</li>
 * </ul>
 *
 * <p>The cost, stated plainly because it is a real limitation: only
 * containers can use these. Browsing the share over SSH, or Samba
 * re-sharing it to the house, needs a host mount and is deliberately not
 * done here.
 *
 * <p><b>Credentials</b> are stored in the volume's driver options, which
 * anyone holding the docker socket can read — the same trust boundary as
 * the {@code .env} files Aurora already writes. They are not encrypted at
 * rest, which is why the UI asks for a dedicated account rather than the
 * owner's main NAS login.
 */
@Service
public class NetworkMountService {

  private static final Logger log = LoggerFactory.getLogger(NetworkMountService.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** Where the record of what has been attached lives. */
  static final String SETTINGS_KEY = "storage.network_mounts";

  /** Prefix so a glance at {@code docker volume ls} explains itself. */
  static final String VOLUME_PREFIX = "aurora_nas_";

  private final CommandRunner commands;
  private final SettingsRepo settings;
  private final AuditEventRepo audit;
  private final AuroraUid uid;

  public NetworkMountService(CommandRunner commands, SettingsRepo settings,
                             AuditEventRepo audit, AuroraUid uid) {
    this.commands = commands;
    this.settings = settings;
    this.audit = audit;
    this.uid = uid;
  }

  /** A folder on a network device, attached to this box. */
  public record Mount(
      String id,
      String label,
      String address,
      String share,
      String username,
      String volume,
      boolean readOnly
  ) {}

  /** Outcome of attaching. {@code detail} is written for the owner, not the log. */
  public record AttachResult(boolean ok, Mount mount, String detail) {}

  /**
   * Attach {@code //address/share}.
   *
   * <p>Verified before it is remembered: the volume is created and then a
   * throwaway container is asked to read it. Docker creates a CIFS volume
   * happily whether or not the credentials are right — the mount is not
   * attempted until something uses it — so recording success at creation
   * time would tell the owner it worked and leave them to discover
   * otherwise when an app fails to start days later.
   */
  public synchronized AttachResult attach(String label, String address, String share,
                                          String username, String password, boolean readOnly) {
    if (address == null || address.isBlank() || share == null || share.isBlank()) {
      return new AttachResult(false, null, "Aurora needs the device and the folder name.");
    }

    String id = idFor(address, share);
    String volume = VOLUME_PREFIX + id;

    var opts = new LinkedHashMap<String, String>();
    opts.put("type", "cifs");
    opts.put("device", "//" + address.trim() + "/" + share.trim());
    opts.put("o", mountOptions(username, password, readOnly));

    try {
      // Idempotent: re-attaching the same share with new credentials should
      // replace the old definition rather than fail on a name clash.
      removeVolumeQuietly(volume);

      List<String> argv = new ArrayList<>(List.of("docker", "volume", "create",
          "--driver", "local", "--name", volume));
      for (var e : opts.entrySet()) {
        argv.add("--opt");
        argv.add(e.getKey() + "=" + e.getValue());
      }
      int exit = commands.stream(null, Map.of(), argv, line -> { });
      if (exit != 0) {
        return new AttachResult(false, null, "Aurora couldn't set up that folder.");
      }

      String failure = verify(volume);
      if (failure != null) {
        // Do not leave a volume behind that does not work: it would show up
        // in docker volume ls looking like a working attachment.
        removeVolumeQuietly(volume);
        return new AttachResult(false, null, failure);
      }

      Mount mount = new Mount(id, label == null || label.isBlank() ? share : label.trim(),
          address.trim(), share.trim(), username, volume, readOnly);
      remember(mount);
      audit.record(null, "storage.network_mount.attach", volume, null);
      log.info("attached //{}/{} as docker volume {}", address, share, volume);
      return new AttachResult(true, mount, null);
    } catch (Exception e) {
      log.warn("could not attach //{}/{}: {}", address, share, e.toString());
      removeVolumeQuietly(volume);
      return new AttachResult(false, null, "Aurora couldn't set up that folder.");
    }
  }

  /**
   * Actually mount it once, and report what a person can act on.
   *
   * <p>The messages map the two mistakes people make — wrong password,
   * wrong folder name — onto sentences that say which one it was. Docker
   * surfaces both as a mount failure with a kernel errno, which is not
   * something to put in front of anyone.
   */
  private String verify(String volume) throws Exception {
    var out = new StringBuilder();
    int exit = commands.stream(null, Map.of(),
        List.of("docker", "run", "--rm", "-v", volume + ":/probe:ro",
            "--entrypoint", "ls", auroraImage(), "-1", "/probe"),
        line -> out.append(line).append('\n'));

    if (exit == 0) return null;

    String text = out.toString().toLowerCase(Locale.ROOT);
    if (text.contains("permission denied") || text.contains("13")) {
      return "That username and password didn't open the folder.";
    }
    if (text.contains("no such file") || text.contains("2")) {
      return "That folder isn't there. Check the name on the device.";
    }
    if (text.contains("host is down") || text.contains("timed out") || text.contains("112")) {
      return "That device didn't answer. It may be asleep.";
    }
    return "Aurora couldn't open that folder.";
  }

  /**
   * Options for the kernel's cifs driver.
   *
   * <p>{@code uid}/{@code gid} matter: without them every file appears
   * owned by root inside the container and apps running as the aurora user
   * cannot read their own media. {@code vers=3.0} because SMB1 is off by
   * default on every current NAS and negotiating down to it is a security
   * regression, not a compatibility win.
   */
  private String mountOptions(String username, String password, boolean readOnly) {
    var parts = new ArrayList<String>();
    if (username == null || username.isBlank()) {
      parts.add("guest");
    } else {
      parts.add("username=" + username);
      parts.add("password=" + (password == null ? "" : password));
    }
    parts.add("uid=" + uid.value());
    parts.add("gid=" + uid.value());
    parts.add("vers=3.0");
    parts.add("iocharset=utf8");
    if (readOnly) parts.add("ro");
    return String.join(",", parts);
  }

  /** Everything attached, in the order it was added. */
  public List<Mount> list() {
    try {
      String raw = settings.get(SETTINGS_KEY).orElse("[]");
      ArrayNode arr = (ArrayNode) JSON.readTree(raw);
      List<Mount> out = new ArrayList<>();
      arr.forEach(n -> out.add(new Mount(
          n.path("id").asText(), n.path("label").asText(), n.path("address").asText(),
          n.path("share").asText(),
          n.path("username").isNull() ? null : n.path("username").asText(),
          n.path("volume").asText(), n.path("readOnly").asBoolean())));
      return out;
    } catch (Exception e) {
      log.warn("could not read network mounts: {}", e.toString());
      return List.of();
    }
  }

  /** Forget an attachment and remove its volume. */
  public synchronized boolean detach(String id) {
    List<Mount> mounts = new ArrayList<>(list());
    Mount found = mounts.stream().filter(m -> m.id().equals(id)).findFirst().orElse(null);
    if (found == null) return false;
    mounts.removeIf(m -> m.id().equals(id));
    write(mounts);
    removeVolumeQuietly(found.volume());
    audit.record(null, "storage.network_mount.detach", found.volume(), null);
    return true;
  }

  private void remember(Mount mount) {
    List<Mount> mounts = new ArrayList<>(list());
    mounts.removeIf(m -> m.id().equals(mount.id()));
    mounts.add(mount);
    write(mounts);
  }

  private void write(List<Mount> mounts) {
    ArrayNode arr = JSON.createArrayNode();
    for (Mount m : mounts) {
      ObjectNode n = arr.addObject();
      n.put("id", m.id());
      n.put("label", m.label());
      n.put("address", m.address());
      n.put("share", m.share());
      if (m.username() == null) n.putNull("username"); else n.put("username", m.username());
      n.put("volume", m.volume());
      n.put("readOnly", m.readOnly());
      // Deliberately absent: the password. It lives in the docker volume
      // definition, and copying it into Aurora's own settings would put the
      // same secret in a second place for no gain.
    }
    settings.put(SETTINGS_KEY, arr.toString());
  }

  private void removeVolumeQuietly(String volume) {
    try {
      commands.stream(null, Map.of(), List.of("docker", "volume", "rm", "-f", volume),
          line -> { });
    } catch (Exception ignored) {
      // Already gone, or in use by a running container. Neither is worth
      // failing the caller over.
    }
  }

  /** The image used for the read probe: our own, so nothing new is pulled. */
  private String auroraImage() {
    String v = System.getenv("AURORA_BUILD_VERSION");
    return "ghcr.io/tomaytotomato/aurora:" + (v == null || v.isBlank() ? "0.1.0" : v);
  }

  /** Stable, readable, and unique per share. */
  static String idFor(String address, String share) {
    return (address + "_" + share).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
  }

  /** The uid apps run as, injected so tests do not depend on the environment. */
  public interface AuroraUid {
    int value();
  }
}
