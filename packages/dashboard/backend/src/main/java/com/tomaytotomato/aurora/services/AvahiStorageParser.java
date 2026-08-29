package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.domain.NetworkStorageDevice;
import com.tomaytotomato.aurora.domain.NetworkStorageDevice.Protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses {@code avahi-browse -alrpt} output into storage devices.
 *
 * <p>Kept as a pure function of a string so it can be tested against the
 * shapes real devices produce without a network, a container, or a NAS —
 * which matters here, because the alternative approach (point it at one
 * NAS, read what comes back, code to that) produces something that works
 * on exactly one person's network.
 *
 * <p>The format is documented and stable: one record per line,
 * semicolon-separated, where {@code =} lines are resolved services:
 *
 * <pre>
 * =;eth0;IPv4;Living\032Room\032NAS;_smb._tcp;local;nas.local;192.168.0.50;445;"model=DS220j"
 *   ^  ^    ^    ^                   ^         ^     ^         ^            ^    ^
 *   0  1    2    3 name              4 type    5     6 host    7 address    8    9 txt
 * </pre>
 *
 * <p>Names are escaped: {@code \032} is a space, and {@code \\} a literal
 * backslash. A device called "Bruce's NAS" arrives with the apostrophe
 * intact but the spaces escaped, and printing the raw form to a user is
 * the kind of small ugliness that makes a product feel unfinished.
 */
final class AvahiStorageParser {

  private AvahiStorageParser() {}

  /** mDNS service types that mean "I can store your files". */
  static final Map<String, String> SERVICE_TYPES = Map.of(
      "_smb._tcp", Protocol.SMB,
      "_nfs._tcp", Protocol.NFS,
      "_afpovertcp._tcp", Protocol.AFP,
      "_adisk._tcp", Protocol.TIME_MACHINE
  );

  /**
   * Fold resolved mDNS records into one entry per device.
   *
   * <p>Grouping is by address, not by name: a NAS advertises SMB, AFP and
   * Time Machine as three separate records, usually with the same name but
   * not always (Synology publishes the Time Machine record under a
   * different label). Three cards for one box would be a bug the owner
   * would have to reason about.
   */
  static List<NetworkStorageDevice> parse(String avahiOutput) {
    if (avahiOutput == null || avahiOutput.isBlank()) return List.of();

    // Insertion-ordered so the output is stable for the UI and for tests.
    Map<String, Builder> byAddress = new LinkedHashMap<>();

    for (String raw : avahiOutput.split("\n")) {
      String line = raw.trim();
      if (line.isEmpty() || !line.startsWith("=")) continue;   // only resolved records

      String[] f = splitUnescaped(line);
      if (f.length < 9) continue;

      String type = f[4];
      String protocol = SERVICE_TYPES.get(type);
      if (protocol == null) continue;

      String address = f[7];
      if (address.isBlank()) continue;
      // Link-local IPv6 (fe80::…%iface) is not somewhere we can mount from
      // without the scope, and it is never the only way to reach a NAS.
      if (address.startsWith("fe80:")) continue;

      int port = parsePort(f[8]);
      String txt = f.length > 9 ? f[9] : "";

      Builder b = byAddress.computeIfAbsent(address, a -> new Builder());
      b.address = address;
      if (b.name == null || protocolRank(protocol) < protocolRank(b.namedBy)) {
        // Prefer the name from the protocol we would actually use, so a
        // Time Machine record does not rename the whole device.
        b.name = unescape(f[3]);
        b.namedBy = protocol;
      }
      if (b.host == null && !f[6].isBlank()) b.host = f[6];
      if (b.model == null) b.model = modelFrom(txt);
      b.protocols.putIfAbsent(protocol, port);
    }

    List<NetworkStorageDevice> out = new ArrayList<>();
    for (Builder b : byAddress.values()) {
      List<Protocol> protocols = b.protocols.entrySet().stream()
          .sorted((x, y) -> Integer.compare(protocolRank(x.getKey()), protocolRank(y.getKey())))
          .map(e -> new Protocol(e.getKey(), e.getValue()))
          .toList();
      out.add(new NetworkStorageDevice(
          b.name == null ? b.address : b.name,
          b.host, b.address, protocols, b.model, false));
    }
    return out;
  }

  /**
   * Preference order. SMB first because it is what every platform in a
   * house can mount without setup; NFS is faster but Windows and phones
   * make it awkward; AFP is deprecated by Apple; Time Machine is a role,
   * not a way to store general files.
   */
  private static int protocolRank(String protocol) {
    if (protocol == null) return 99;
    return switch (protocol) {
      case Protocol.SMB -> 0;
      case Protocol.NFS -> 1;
      case Protocol.AFP -> 2;
      case Protocol.TIME_MACHINE -> 3;
      default -> 98;
    };
  }

  /**
   * The device's own description of itself, if it published one. Several
   * vendors use {@code model=}; Apple's {@code _device-info} uses the same
   * key. Anything else is left null rather than guessed from the hostname,
   * because "DiskStation" in a hostname does not make it a Synology.
   */
  private static String modelFrom(String txt) {
    if (txt == null || txt.isBlank()) return null;
    for (String part : txt.split("\" \"")) {
      String cleaned = part.replace("\"", "").trim();
      int eq = cleaned.indexOf('=');
      if (eq <= 0) continue;
      String key = cleaned.substring(0, eq).trim().toLowerCase();
      String value = cleaned.substring(eq + 1).trim();
      if (("model".equals(key) || "usmb".equals(key)) && !value.isBlank()) return value;
    }
    return null;
  }

  private static int parsePort(String s) {
    try {
      return Integer.parseInt(s.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /**
   * Split on semicolons that are not escaped. A device name containing a
   * semicolon arrives as {@code \;} and must not break the field count.
   */
  private static String[] splitUnescaped(String line) {
    List<String> fields = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean escaped = false;
    for (char c : line.toCharArray()) {
      if (escaped) {
        cur.append('\\').append(c);
        escaped = false;
      } else if (c == '\\') {
        escaped = true;
      } else if (c == ';') {
        fields.add(cur.toString());
        cur.setLength(0);
      } else {
        cur.append(c);
      }
    }
    fields.add(cur.toString());
    return fields.toArray(new String[0]);
  }

  /** {@code Living\032Room\032NAS} → {@code Living Room NAS}. */
  static String unescape(String s) {
    if (s == null) return null;
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 3 < s.length()
          && Character.isDigit(s.charAt(i + 1))
          && Character.isDigit(s.charAt(i + 2))
          && Character.isDigit(s.charAt(i + 3))) {
        out.append((char) Integer.parseInt(s.substring(i + 1, i + 4)));
        i += 3;
      } else if (c == '\\' && i + 1 < s.length()) {
        out.append(s.charAt(i + 1));
        i++;
      } else {
        out.append(c);
      }
    }
    return out.toString().trim();
  }

  private static final class Builder {
    String name;
    String namedBy;
    String host;
    String address;
    String model;
    final Map<String, Integer> protocols = new LinkedHashMap<>();
  }
}
