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
  private final StateFileService stateFiles;

  public SystemService(AuroraProperties props, DockerService docker, StateFileService stateFiles) {
    this.props = props;
    this.docker = docker;
    this.stateFiles = stateFiles;
  }

  /**
   * Structured snapshot the dashboard-home header + System card consume
   * via {@code GET /api/system}. Fields match the frontend
   * {@code SystemInfo} interface (camelCase). Every value is best-effort:
   * missing inputs return {@code null} so the frontend can render an em-dash
   * rather than {@code NaN}/{@code undefined}.
   *
   * <p>Hostname + domain are read from {@code .state.yml} — never from
   * {@code os.hostname()} / container hostname (D10, D4). This closes the
   * {@code be1523c08f0f.undefined} regression captured 2026-08-01.
   *
   * <p>{@code capabilities.metrics} is deliberately {@code false} in iter-1:
   * the frontend gates the metrics fetch on this flag so no 404 is issued
   * from {@code /dashboard/home} until a real timeseries backend lands
   * (UX_SPEC_DASHBOARD §6 non-goal).
   */
  public Map<String, Object> info() {
    Map<String, Object> out = new LinkedHashMap<>();
    var state = stateFiles.readState();
    out.put("hostname", state.hostname());
    out.put("domain", state.domain());
    out.put("lanIp", detectLanIp());
    out.put("distro", readDistro());
    out.put("kernel", readKernel());
    out.put("uptimeSeconds", hostUptimeSeconds());
    Map<String, Object> cpu = cpu();
    Object threads = cpu.get("threads");
    out.put("cpuCount", threads instanceof Number n ? n.intValue() : null);
    Map<String, Long> mem = readMemInfo();
    Long memTotal = mem.get("MemTotal");
    Long memAvail = mem.get("MemAvailable");
    out.put("memTotalBytes", memTotal);
    // Used = total - available (kernel's own definition; matches `free -b`
    // 'used' more closely than the naive total-free).
    out.put("memUsedBytes",
        (memTotal == null || memAvail == null) ? null : memTotal - memAvail);
    Map<String, Long> disk = readDiskRoot();
    out.put("diskTotalBytes", disk.get("total"));
    out.put("diskUsedBytes", disk.get("used"));
    out.put("dockerVersion", docker.version().orElse(null));
    out.put("containerCount", dockerContainerCount());
    // Capability flags let the frontend gate feature fetches without
    // hard-coding a version check. See UX_SPEC_DASHBOARD §4.5.
    Map<String, Object> capabilities = new LinkedHashMap<>();
    capabilities.put("metrics", false);
    // iter-3 P1b: security-posture module lands with M4. Until then the
    // capability flag stays false so the SPA renders the empty-state
    // view instead of fabricated score/findings, and the sidebar
    // hides the /security link.
    capabilities.put("securityScanner", false);
    out.put("capabilities", capabilities);
    return out;
  }

  /**
   * Read {@code .state.yml} as a shape the frontend can consume via
   * {@code GET /api/system/state}. camelCase to match the rest of the
   * dashboard API surface; the file itself uses snake_case per the
   * bash script convention.
   */
  public Map<String, Object> stateSnapshot() {
    var s = stateFiles.readState();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("bootstrapVersion", s.bootstrapVersion());
    out.put("hostname", s.hostname());
    out.put("domain", s.domain());
    out.put("installedAt", s.installedAt());
    out.put("enabled", s.enabled() == null ? List.of() : s.enabled());
    out.put("profiles", s.profiles() == null ? List.of() : s.profiles());
    return out;
  }

  private Long hostUptimeSeconds() {
    // Prefer host uptime from /host/proc/uptime; falls back to JVM uptime
    // so the value is always finite (never null → avoids NaN downstream).
    Path p = Path.of(props.hostProcPath()).resolve("uptime");
    if (!Files.isRegularFile(p)) p = Path.of("/proc/uptime");
    if (Files.isRegularFile(p)) {
      try {
        String s = Files.readString(p).trim();
        String[] parts = s.split("\\s+");
        if (parts.length > 0) {
          double secs = Double.parseDouble(parts[0]);
          return (long) secs;
        }
      } catch (Exception ignore) { /* fall through */ }
    }
    return ManagementFactory.getRuntimeMXBean().getUptime() / 1000L;
  }

  /**
   * Count of aurora-managed containers currently running.
   *
   * <p>Scope: matches whatever {@link DockerService#listProjectContainers()}
   * returns — iter-1 A1 broadened that from {@code project=aurora} to
   * {@code aurora} OR {@code aurora-*}, so the number now spans the
   * dashboard container itself, core (caddy), and every per-package
   * stack. Filtered here to state={@code running} so exited/dead
   * containers don't inflate the header's "Containers N" pill.
   *
   * <p>Non-aurora containers on the same host (e.g. an unrelated
   * {@code docker run nextcloud}) are intentionally excluded so the
   * count reflects what Aurora can act on, not the raw output of
   * {@code docker ps}.
   */
  private Integer dockerContainerCount() {
    try {
      var xs = docker.listProjectContainers();
      if (xs == null) return null;
      int running = 0;
      for (var c : xs) {
        if ("running".equalsIgnoreCase(c.getState())) running++;
      }
      return running;
    } catch (Exception e) {
      log.debug("dockerContainerCount failed: {}", e.getMessage());
      return null;
    }
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
   * <p>Hostname + domain come from {@code .state.yml} via SnakeYAML (same
   * parser as {@link #info()}). Container-hostname fallback (iter-3 TD2)
   * has been removed so the pre-onboarding welcome screen never leaks a
   * Docker container ID like {@code be1523c08f0f} — mirroring the D4
   * regression fix for {@code /api/system}.
   *
   * <p>Distro + kernel + resource facts stay best-effort (may return
   * {@code null}) so the endpoint always renders.
   */
  public Map<String, Object> env() {
    Map<String, Object> out = new HashMap<>();
    var state = stateFiles.readState();
    // Nulls are fine — the wizard treats an empty hostname as "unset" and
    // prompts the operator, whereas a container ID would masquerade as a
    // real host identity (D4 / iter-3 TD2).
    out.put("hostname", state.hostname());
    out.put("domain", state.domain());
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

  // iter-3 TD2: readStateYml() (grep-based YAML parser) removed.
  // env() now reads .state.yml via StateFileService (SnakeYAML), the same
  // parser info() uses. One YAML parser, one code path for identity.

  /** Public accessor for other services (StatusProbeService) that need the LAN IP. */
  public String lanIp() {
    return detectLanIp();
  }

  private String detectLanIp() {
    // Preferred path: read the host's routing table from a bind-mounted /proc.
    // /proc/net is per-netns, so <hostProcPath>/net/fib_trie inside a
    // container reflects the *container's* netns (e.g. the docker bridge IP)
    // even though the file is bind-mounted from the host. To get the host's
    // real routing table we read PID 1's netns file: <hostProcPath>/1/net/fib_trie.
    // Fall back to the legacy /host_root paths (older deploys) and finally
    // the container-scoped /proc, then NetworkInterface as a last resort.
    List<Path> candidates = new ArrayList<>();
    String hostProc = props.hostProcPath();
    if (hostProc != null && !hostProc.isBlank()) {
      candidates.add(Path.of(hostProc, "1/net/fib_trie"));
      candidates.add(Path.of(hostProc, "net/fib_trie"));
    }
    candidates.add(Path.of("/host_root/proc/1/net/fib_trie"));
    candidates.add(Path.of("/host_root/proc/net/fib_trie"));
    candidates.add(Path.of("/proc/net/fib_trie"));
    for (Path p : candidates) {
      if (!Files.isReadable(p)) continue;
      try {
        String best = pickBestLanIp(parseFibTrieHostLocals(p));
        if (best != null) return best;
      } catch (IOException e) {
        log.debug("detectLanIp fib_trie {} failed: {}", p, e.getMessage());
      }
    }
    return detectLanIpViaNetworkInterface();
  }

  /**
   * Parse /proc/net/fib_trie for IPv4 addresses tagged {@code host LOCAL}.
   * The format is a text tree; each "host LOCAL" line is preceded by a
   * {@code |-- <ip>} line one row above (with an intervening {@code /32} row).
   * Package-private for testing.
   */
  static List<String> parseFibTrieHostLocals(Path p) throws IOException {
    List<String> out = new ArrayList<>();
    List<String> lines = Files.readAllLines(p);
    String prevIp = null;
    for (String raw : lines) {
      String line = raw.trim();
      // Subtree boundary: `+-- <cidr>` marks a new subtree. Reset prevIp so a
      // malformed / truncated fib_trie can't attribute a stray `host LOCAL`
      // to an IP that belonged to the previous sibling subtree.
      if (line.startsWith("+--")) {
        prevIp = null;
        continue;
      }
      // Track most recent "|-- <ipv4>" line as we walk down the tree.
      if (line.startsWith("|-- ")) {
        String candidate = line.substring(4).trim();
        if (candidate.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
          prevIp = candidate;
        }
      } else if (line.endsWith("host LOCAL") && prevIp != null) {
        if (!out.contains(prevIp)) out.add(prevIp);
      }
    }
    return out;
  }

  String pickBestLanIp(List<String> candidates) {
    String best = null;
    int bestScore = 0;
    for (String ip : candidates) {
      int s = scoreLanCandidate(ip);
      if (s > bestScore) {
        bestScore = s;
        best = ip;
      }
    }
    return best;
  }

  /**
   * Instance-scoped scorer: applies the pure universal rules, then rejects
   * anything matching a configurable excluded CIDR from AuroraProperties.
   */
  int scoreLanCandidate(String ip) {
    int base = scoreLanCandidatePure(ip);
    if (base == 0) return 0;
    List<String> excluded = props.lanIpExcludedCidrs();
    if (excluded != null) {
      for (String cidr : excluded) {
        if (inCidr(ip, cidr)) return 0;
      }
    }
    return base;
  }

  /**
   * Universal scoring rules with no box-specific exclusions. Reject values
   * are truly universal (loopback, link-local, multicast, CGNAT, 0.0.0.0).
   * Package-private for testing.
   */
  static int scoreLanCandidatePure(String ip) {
    int[] o = new int[4];
    String[] parts = ip.split("\\.");
    if (parts.length != 4) return 0;
    try {
      for (int i = 0; i < 4; i++) {
        o[i] = Integer.parseInt(parts[i]);
        if (o[i] < 0 || o[i] > 255) return 0;
      }
    } catch (NumberFormatException e) {
      return 0;
    }
    // Loopback / link-local / broadcast / multicast — reject.
    if (o[0] == 127) return 0;
    if (o[0] == 169 && o[1] == 254) return 0;
    if (o[0] >= 224) return 0;
    if (o[0] == 0) return 0;
    // CGNAT 100.64.0.0/10 — often ProtonVPN / carrier NAT. Reject.
    if (o[0] == 100 && o[1] >= 64 && o[1] <= 127) return 0;
    // 192.168.0.0/16 — canonical home LAN.
    if (o[0] == 192 && o[1] == 168) return 100;
    // 10.0.0.0/8 — private.
    if (o[0] == 10) return 50;
    // 172.16.0.0/12 — private.
    if (o[0] == 172 && o[1] >= 16 && o[1] <= 31) return 20;
    return 0;
  }

  /**
   * Test whether {@code ip} lies inside {@code cidr}. Returns false on any
   * parse failure — never throws. Supports IPv4 CIDRs like "192.168.0.0/16",
   * boundary /32 (single host), and /0 (matches everything). Package-private
   * for testing.
   */
  static boolean inCidr(String ip, String cidr) {
    if (ip == null || cidr == null) return false;
    int slash = cidr.indexOf('/');
    if (slash < 0) return false;
    String base = cidr.substring(0, slash);
    int prefix;
    try {
      prefix = Integer.parseInt(cidr.substring(slash + 1));
    } catch (NumberFormatException e) {
      return false;
    }
    if (prefix < 0 || prefix > 32) return false;
    long ipBits = ipToLong(ip);
    long baseBits = ipToLong(base);
    if (ipBits < 0 || baseBits < 0) return false;
    if (prefix == 0) return true;
    long mask = (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
    return (ipBits & mask) == (baseBits & mask);
  }

  private static long ipToLong(String ip) {
    String[] parts = ip.split("\\.");
    if (parts.length != 4) return -1;
    long out = 0L;
    try {
      for (int i = 0; i < 4; i++) {
        int v = Integer.parseInt(parts[i]);
        if (v < 0 || v > 255) return -1;
        out = (out << 8) | v;
      }
    } catch (NumberFormatException e) {
      return -1;
    }
    return out;
  }

  private String detectLanIpViaNetworkInterface() {
    // Fallback: scan local NetworkInterfaces. Inside a container this returns
    // the docker bridge IP, but it's better than null in dev.
    try {
      var ifaces = java.net.NetworkInterface.getNetworkInterfaces();
      while (ifaces != null && ifaces.hasMoreElements()) {
        var iface = ifaces.nextElement();
        if (iface.isLoopback() || !iface.isUp()) continue;
        String name = iface.getName();
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
      log.debug("detectLanIpViaNetworkInterface failed: {}", e.getMessage());
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
