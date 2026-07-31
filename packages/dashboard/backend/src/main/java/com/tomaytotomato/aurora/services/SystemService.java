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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
    out.put("cpu", cpu());
    out.put("memory", readMemInfo());
    out.put("disks", disks());
    out.put("gpu", gpu());
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
    // Host resource snapshot for the welcome screen + resource-warning logic.
    out.put("cpu", cpu());
    out.put("memory", readMemInfo());
    out.put("disks", disks());
    out.put("gpu", gpu());
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

  /** Public accessor for meminfo used by the plan evaluator. Same data
   *  shape (MemTotal / MemAvailable / MemFree in bytes). */
  public Map<String, Long> readMemInfoPublic() {
    return readMemInfo();
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

  // ------------------------------------------------------------------
  // Host resource snapshot (v0.2)
  // ------------------------------------------------------------------

  /**
   * CPU facts from /host/proc/cpuinfo. Returns null-valued fields on
   * failure rather than throwing so the welcome screen always renders.
   */
  public Map<String, Object> cpu() {
    Map<String, Object> out = new HashMap<>();
    Path p = Path.of(props.hostProcPath()).resolve("cpuinfo");
    if (!Files.isRegularFile(p)) p = Path.of("/proc/cpuinfo");
    String model = null;
    Double mhz = null;
    int threads = 0;
    var physicalIds = new java.util.HashSet<String>();
    var coreIds = new java.util.HashSet<String>();
    String currentPhys = null;
    if (Files.isRegularFile(p)) {
      try {
        for (String line : Files.readAllLines(p)) {
          int colon = line.indexOf(':');
          if (colon < 0) continue;
          String key = line.substring(0, colon).trim();
          String val = line.substring(colon + 1).trim();
          switch (key) {
            case "processor" -> threads++;
            case "model name" -> { if (model == null) model = val; }
            case "cpu MHz" -> {
              if (mhz == null) {
                try { mhz = Double.parseDouble(val); } catch (NumberFormatException ignore) {}
              }
            }
            case "physical id" -> { currentPhys = val; physicalIds.add(val); }
            case "core id" -> coreIds.add((currentPhys == null ? "" : currentPhys) + ":" + val);
            default -> {}
          }
        }
      } catch (IOException e) {
        log.debug("readCpuInfo failed: {}", e.getMessage());
      }
    }
    // Fallback: cgroup-clamped view of processor count.
    if (threads == 0) threads = Runtime.getRuntime().availableProcessors();
    int cores = coreIds.isEmpty() ? threads : coreIds.size();
    int sockets = physicalIds.isEmpty() ? 1 : physicalIds.size();

    out.put("model", model);
    out.put("threads", threads);
    out.put("cores", cores);
    out.put("sockets", sockets);
    out.put("mhz", mhz);
    out.put("load1", readLoadAvg1());
    return out;
  }

  private Double readLoadAvg1() {
    Path p = Path.of(props.hostProcPath()).resolve("loadavg");
    if (!Files.isRegularFile(p)) p = Path.of("/proc/loadavg");
    if (!Files.isRegularFile(p)) return null;
    try {
      String s = Files.readString(p).trim();
      String[] parts = s.split("\\s+");
      if (parts.length > 0) return Double.parseDouble(parts[0]);
    } catch (Exception ignore) { /* fall through */ }
    return null;
  }

  private static final java.util.Set<String> REAL_FS_TYPES = java.util.Set.of(
      "ext2", "ext3", "ext4", "xfs", "btrfs", "zfs", "f2fs", "jfs",
      "reiserfs", "nilfs2", "bcachefs"
  );

  /**
   * Enumerate real disk mounts. Parses /host/proc/mounts, filters to
   * block-backed filesystems, and stats each via the /host_root bind mount.
   * Returns [] rather than throwing if /host_root isn't mounted (dev/mac).
   */
  public List<Map<String, Object>> disks() {
    var out = new ArrayList<Map<String, Object>>();
    Path mounts = Path.of(props.hostProcPath()).resolve("mounts");
    if (!Files.isRegularFile(mounts)) mounts = Path.of("/proc/mounts");
    Path hostRoot = Path.of("/host_root");
    boolean haveHostRoot = Files.isDirectory(hostRoot);

    // Docker quirk: /host/proc (bind of /proc) is the container's own
    // procfs, not the host's — procfs is per-namespace, so what we see
    // here is our own mount table. That's actually what we want: the
    // /host_root bind mount and any nested host directories under it
    // appear here, and their mount paths are already valid container
    // paths we can statvfs directly. Display path strips the /host_root
    // prefix so the operator sees the host's real mount point.
    var seenDisplayMounts = new java.util.HashSet<String>();
    if (Files.isRegularFile(mounts)) {
      try {
        for (String line : Files.readAllLines(mounts)) {
          // format: <device> <mount> <fstype> <opts> <dump> <pass>
          String[] parts = line.split(" ");
          if (parts.length < 3) continue;
          String device = parts[0];
          String rawMount = parts[1].replace("\\040", " ");
          String fstype = parts[2];
          if (!REAL_FS_TYPES.contains(fstype)) continue;
          if (!device.startsWith("/dev/")) continue;    // skip loop/bind

          // Only report mounts we can actually statvfs. When /host_root is
          // present, that means /host_root itself + anything nested below
          // it. Without /host_root, fall back to whatever the container
          // can see directly (dev boxes / tests).
          String statPath;
          String displayMount;
          if (haveHostRoot) {
            if (rawMount.equals("/host_root")) {
              statPath = "/host_root";
              displayMount = "/";
            } else if (rawMount.startsWith("/host_root/")) {
              statPath = rawMount;
              displayMount = rawMount.substring("/host_root".length());
              // Skip host-side pseudo/virtual mounts nested under /host_root
              // (they get re-listed here because docker inherited them).
              if (displayMount.startsWith("/proc")
                  || displayMount.startsWith("/sys")
                  || displayMount.startsWith("/dev")
                  || displayMount.startsWith("/run")
                  || displayMount.startsWith("/tmp")
                  || displayMount.startsWith("/var/lib/docker")
                  || displayMount.startsWith("/snap")) continue;
            } else {
              // A /dev/* mount not under /host_root — that's a container
              // bind mount (e.g. /data, /repo) pointing at the same host
              // disk. Skip to avoid duplicate rows for the same partition.
              continue;
            }
          } else {
            statPath = rawMount;
            displayMount = rawMount;
          }
          if (!seenDisplayMounts.add(displayMount)) continue;

          Map<String, Object> d = new LinkedHashMap<>();
          d.put("device", device);
          d.put("mount", displayMount);
          d.put("fstype", fstype);

          try {
            var store = Files.getFileStore(Path.of(statPath));
            long total = store.getTotalSpace();
            long free  = store.getUsableSpace();
            d.put("total_bytes", total);
            d.put("free_bytes", free);
            d.put("used_bytes", total - store.getUnallocatedSpace());
          } catch (IOException e) {
            log.debug("statvfs {} failed: {}", statPath, e.getMessage());
          }
          out.add(d);
        }
      } catch (IOException e) {
        log.warn("disks(): failed to read {}: {}", mounts, e.getMessage());
      }
    }
    // Sort largest first so the primary drive lands at the top.
    out.sort((a, b) -> Long.compare(
        ((Number) b.getOrDefault("total_bytes", 0L)).longValue(),
        ((Number) a.getOrDefault("total_bytes", 0L)).longValue()));
    return out;
  }

  /**
   * Best-effort GPU detection. v0.2 covers nvidia only — checks for the
   * kernel-loaded nvidia driver via /host/proc/driver/nvidia. AMD/Intel
   * would need /sys/class/drm which isn't currently mounted.
   */
  public Map<String, Object> gpu() {
    Map<String, Object> out = new LinkedHashMap<>();
    Path nv = Path.of(props.hostProcPath()).resolve("driver/nvidia");
    if (!Files.isDirectory(nv)) nv = Path.of("/proc/driver/nvidia");
    boolean present = Files.isDirectory(nv);
    out.put("present", present);
    out.put("vendor", present ? "nvidia" : null);
    // Attempt to read model + memory from /proc/driver/nvidia/gpus/*/information.
    if (present) {
      Path gpus = nv.resolve("gpus");
      if (Files.isDirectory(gpus)) {
        try (var ds = Files.newDirectoryStream(gpus, Files::isDirectory)) {
          for (Path g : ds) {
            Path info = g.resolve("information");
            if (!Files.isRegularFile(info)) continue;
            for (String line : Files.readAllLines(info)) {
              int colon = line.indexOf(':');
              if (colon < 0) continue;
              String key = line.substring(0, colon).trim().toLowerCase();
              String val = line.substring(colon + 1).trim();
              if (key.equals("model")) out.put("model", val);
            }
            break; // v0.2: report only the first GPU
          }
        } catch (IOException ignore) { /* best-effort */ }
      }
    }
    return out;
  }
}
