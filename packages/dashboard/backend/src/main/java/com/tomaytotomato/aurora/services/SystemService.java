package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Collect host + JVM facts for the /api/system endpoint. Everything here is
 * best-effort; partial failure returns partial data rather than 500.
 */
@Service
public class SystemService {

  private static final Logger log = LoggerFactory.getLogger(SystemService.class);
  private final AuroraProperties props;
  private final DockerService docker;

  public SystemService(AuroraProperties props, DockerService docker) {
    this.props = props;
    this.docker = docker;
  }

  public Map<String, Object> snapshot() {
    Map<String, Object> out = new HashMap<>();
    out.put("hostname", hostname());
    out.put("java_version", System.getProperty("java.version"));
    out.put("uptime_ms", ManagementFactory.getRuntimeMXBean().getUptime());
    out.put("docker_version", docker.version().orElse(null));
    out.put("memory", readMemInfo());
    out.put("disk", readDiskRoot());
    return out;
  }

  /**
   * Small facts safe to expose before login. Used by the onboarding wizard
   * so it can echo the box's identity back to the operator.
   *
   * <p>Prefer host values when they're mounted: reads {@code /repo/.state.yml}
   * for hostname/domain, {@code /host/etc/os-release} for distro, and
   * {@code /host/proc/version} for kernel. Falls back to container-local
   * values so the endpoint always returns something.
   */
  public Map<String, Object> env() {
    Map<String, Object> out = new HashMap<>();
    Map<String, String> stateFile = readStateYml();
    out.put("hostname", stateFile.getOrDefault("hostname", hostname()));
    out.put("domain", stateFile.get("domain"));
    out.put("lanIp", detectLanIp());
    out.put("distro", readDistro());
    out.put("kernel", readKernel());
    out.put("dockerVersion", docker.version().orElse(null));
    return out;
  }

  private Map<String, String> readStateYml() {
    Map<String, String> out = new HashMap<>();
    Path p = Path.of(props.repoPath()).resolve(".state.yml");
    if (!Files.isRegularFile(p)) return out;
    try {
      for (String line : Files.readAllLines(p)) {
        int colon = line.indexOf(':');
        if (colon < 0) continue;
        String key = line.substring(0, colon).trim();
        String val = line.substring(colon + 1).trim();
        if (val.startsWith("\"") && val.endsWith("\"")) val = val.substring(1, val.length() - 1);
        if (val.startsWith("'") && val.endsWith("'")) val = val.substring(1, val.length() - 1);
        if (key.equals("hostname") || key.equals("domain")) out.put(key, val);
      }
    } catch (IOException e) {
      log.debug("readStateYml failed: {}", e.getMessage());
    }
    return out;
  }

  private String detectLanIp() {
    // Prefer a private IPv4 that's not loopback/docker. Best-effort;
    // returns null if no interface qualifies.
    try {
      var ifaces = java.net.NetworkInterface.getNetworkInterfaces();
      while (ifaces != null && ifaces.hasMoreElements()) {
        var iface = ifaces.nextElement();
        if (iface.isLoopback() || !iface.isUp()) continue;
        String name = iface.getName();
        // Skip docker bridges, tailscale, VPN tuns.
        if (name.startsWith("docker") || name.startsWith("br-")
            || name.startsWith("veth") || name.startsWith("tun")
            || name.startsWith("tailscale")) continue;
        var addrs = iface.getInetAddresses();
        while (addrs.hasMoreElements()) {
          var a = addrs.nextElement();
          if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) {
            return a.getHostAddress();
          }
        }
      }
    } catch (Exception e) {
      log.debug("detectLanIp failed: {}", e.getMessage());
    }
    return null;
  }

  private String readDistro() {
    // Prefer host-mounted /host/etc/os-release; fall back to container's.
    Path host = Path.of("/host/etc/os-release");
    Path local = Path.of("/etc/os-release");
    Path p = Files.isRegularFile(host) ? host : local;
    if (!Files.isRegularFile(p)) return null;
    try {
      for (String line : Files.readAllLines(p)) {
        if (line.startsWith("PRETTY_NAME=")) {
          String v = line.substring("PRETTY_NAME=".length()).trim();
          if (v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
          return v;
        }
      }
    } catch (IOException e) {
      log.debug("readDistro failed: {}", e.getMessage());
    }
    return null;
  }

  private String readKernel() {
    // /proc/version if the host /proc is bind-mounted; else fall back to JVM.
    Path p = Path.of(props.hostProcPath()).resolve("version");
    if (!Files.isRegularFile(p)) p = Path.of("/proc/version");
    if (Files.isRegularFile(p)) {
      try {
        String s = Files.readString(p).trim();
        // "Linux version 6.1.0-18-amd64 (debian-kernel@lists.debian.org) ..."
        String[] parts = s.split("\\s+", 4);
        if (parts.length >= 3) return parts[2];
      } catch (IOException ignore) { /* fall through */ }
    }
    return System.getProperty("os.name") + " " + System.getProperty("os.version");
  }

  private String hostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return null;
    }
  }

  private Map<String, Long> readMemInfo() {
    Map<String, Long> mem = new HashMap<>();
    Path p = Path.of(props.hostProcPath()).resolve("meminfo");
    if (!Files.isRegularFile(p)) {
      // Fallback to /proc/meminfo directly.
      p = Path.of("/proc/meminfo");
    }
    if (!Files.isRegularFile(p)) return mem;
    try {
      for (String line : Files.readAllLines(p)) {
        // "MemTotal:       16341156 kB"
        int colon = line.indexOf(':');
        if (colon < 0) continue;
        String key = line.substring(0, colon).trim();
        String rest = line.substring(colon + 1).trim();
        String num = rest.split("\\s+")[0];
        try {
          long kb = Long.parseLong(num);
          if (key.equals("MemTotal") || key.equals("MemAvailable") || key.equals("MemFree")) {
            mem.put(key, kb * 1024L);
          }
        } catch (NumberFormatException ignore) { /* skip */ }
      }
    } catch (IOException e) {
      log.warn("readMemInfo failed: {}", e.getMessage());
    }
    return mem;
  }

  private Map<String, Long> readDiskRoot() {
    Map<String, Long> disk = new HashMap<>();
    try {
      var store = Files.getFileStore(Path.of("/"));
      disk.put("total", store.getTotalSpace());
      disk.put("usable", store.getUsableSpace());
      disk.put("used", store.getTotalSpace() - store.getUnallocatedSpace());
    } catch (IOException e) {
      log.warn("readDiskRoot failed: {}", e.getMessage());
    }
    return disk;
  }
}
