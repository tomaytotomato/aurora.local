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
