package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.domain.NetworkStorageDevice;
import com.tomaytotomato.aurora.domain.NetworkStorageDevice.Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds storage that already exists on the owner's network.
 *
 * <p><b>Listening, not scanning.</b> NAS boxes announce themselves over
 * mDNS — Synology, QNAP, TrueNAS, unRAID and a plain Samba install all
 * advertise {@code _smb._tcp} out of the box — so Aurora can find them by
 * listening to announcements it already receives. It does not sweep the
 * subnet for open ports. Port-scanning someone's home network is rude, it
 * trips intrusion detection on exactly the kind of appliance we are looking
 * for, and a box that quietly probes every address on the LAN is not
 * something to install on trust. If listening ever proves insufficient,
 * an explicit "look harder" button with copy explaining what it does is the
 * honest way to add it — not a default.
 *
 * <p><b>No new dependency or privilege.</b> {@code avahi-browse} is already
 * in the image (it shipped with {@code avahi-publish} for
 * {@link MdnsAliasService}) and the container already reaches the host's
 * avahi-daemon over D-Bus. Discovery costs nothing new.
 *
 * <p><b>Advertising is not answering.</b> A device that is asleep, or whose
 * SMB service has died, keeps advertising. Each result is therefore probed
 * with a short TCP connect before being reported reachable, and the two
 * facts are kept separate in the response rather than merged into one
 * optimistic boolean.
 */
@Service
public class NetworkStorageService {

  private static final Logger log = LoggerFactory.getLogger(NetworkStorageService.class);

  /** How long to listen. Long enough for a sleepy NAS, short enough for a page load. */
  private static final int BROWSE_SECONDS = 4;

  /** Per-device connect timeout for the "does it actually answer" probe. */
  private static final int PROBE_TIMEOUT_MS = 800;

  private final CommandRunner commands;

  public NetworkStorageService(CommandRunner commands) {
    this.commands = commands;
  }

  /**
   * Everything on the LAN that says it can store files, newest answer
   * first. Never throws: no avahi, no D-Bus, or a network with nothing on
   * it are all "found nothing", which is a legitimate answer and renders as
   * an honest empty state rather than an error.
   */
  public List<NetworkStorageDevice> discover() {
    String output;
    try {
      output = browse();
    } catch (Exception e) {
      log.debug("network storage discovery unavailable: {}", e.toString());
      return List.of();
    }

    List<NetworkStorageDevice> devices = AvahiStorageParser.parse(output);
    List<NetworkStorageDevice> out = new ArrayList<>(devices.size());
    for (NetworkStorageDevice d : devices) {
      out.add(new NetworkStorageDevice(
          d.name(), d.host(), d.address(), d.protocols(), d.model(), answers(d)));
    }
    return out;
  }

  /**
   * Run the browse. {@code -a} all services, {@code -l} local only (a
   * device on someone else's network is not ours to offer), {@code -r}
   * resolve to address and port, {@code -p} parsable, {@code -t} terminate
   * once the cache is exhausted.
   */
  private String browse() throws IOException, InterruptedException {
    var sb = new StringBuilder();
    commands.stream(null, java.util.Map.of(),
        List.of("timeout", String.valueOf(BROWSE_SECONDS), "avahi-browse", "-alrpt"),
        line -> sb.append(line).append('\n'));
    return sb.toString();
  }

  /**
   * Does the advertised port actually accept a connection? One short
   * connect to the preferred protocol's port — not a login, not a share
   * listing, and never with credentials we were not given.
   */
  private boolean answers(NetworkStorageDevice d) {
    Protocol p = d.protocols().isEmpty() ? null : d.protocols().get(0);
    if (p == null || p.port() <= 0 || d.address() == null) return false;
    try (Socket s = new Socket()) {
      s.connect(new InetSocketAddress(d.address(), p.port()), PROBE_TIMEOUT_MS);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /** Visible for tests, so they can stay unaware of avahi. */
  static List<NetworkStorageDevice> parse(String avahiOutput) {
    return AvahiStorageParser.parse(avahiOutput);
  }
}
