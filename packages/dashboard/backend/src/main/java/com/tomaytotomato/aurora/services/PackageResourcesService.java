package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.persistence.SettingsRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Memory and CPU ceilings for a package: what its manifest declares, what
 * the operator has overridden, and what it is using.
 *
 * <p>Why ceilings matter on this class of machine specifically: a home
 * box has no spare capacity and usually no swap, so a container that runs
 * away takes the whole machine with it and the OOM killer picks its
 * victim from every process on the host. With a limit in place the kernel
 * kills the offender instead, which turns "the server fell over" into
 * "that model was too big".
 *
 * <p>Defaults come from the manifest's {@code resources:} block. Overrides
 * live in the settings table rather than being written back into the
 * manifest, because the manifest is git-tracked and belongs to the
 * package; an operator's local ceiling does not.
 */
@Service
public class PackageResourcesService {

  private static final Logger log = LoggerFactory.getLogger(PackageResourcesService.class);

  private static final String KEY_PREFIX = "resources.";

  private final AuroraProperties props;
  private final SettingsRepo settings;

  public PackageResourcesService(AuroraProperties props, SettingsRepo settings) {
    this.props = props;
    this.settings = settings;
  }

  public Map<String, Object> forPackage(String pkg) {
    Map<String, Object> declared = manifestResources(pkg);

    Map<String, Object> m = new LinkedHashMap<>();
    m.put("package", pkg);
    m.put("defaultMemLimitMb", intOrNull(declared.get("mem_limit_mb")));
    m.put("defaultCpus", numberOrNull(declared.get("cpus")));
    m.put("memLimitMb", intOrNull(override(pkg, "memLimitMb")));
    m.put("cpus", numberOrNull(override(pkg, "cpus")));
    // Live usage lands with the per-package container-stats wiring. Null
    // means "not measured", which the frontend renders as the ceiling with
    // no usage bar — honest, rather than a bar sitting at zero.
    m.put("memUsedMb", null);
    m.put("cpuPct", null);
    return m;
  }

  /** Null on either field clears that override and restores the manifest default. */
  public Map<String, Object> setOverride(String pkg, Integer memLimitMb, Double cpus) {
    if (memLimitMb == null && cpus == null) {
      settings.delete(KEY_PREFIX + pkg);
    } else {
      StringBuilder json = new StringBuilder("{");
      if (memLimitMb != null) json.append("\"memLimitMb\":").append(memLimitMb);
      if (memLimitMb != null && cpus != null) json.append(',');
      if (cpus != null) json.append("\"cpus\":").append(cpus);
      settings.put(KEY_PREFIX + pkg, json.append('}').toString());
    }
    return forPackage(pkg);
  }

  // ------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private Map<String, Object> manifestResources(String pkg) {
    Path p = Path.of(props.repoPath()).resolve("packages").resolve(pkg).resolve("manifest.yml");
    if (!Files.isRegularFile(p)) return Map.of();
    try (var in = Files.newInputStream(p)) {
      Map<String, Object> m = new Yaml().load(in);
      if (m == null) return Map.of();
      Object raw = m.get("resources");
      return raw instanceof Map<?, ?> mm ? (Map<String, Object>) mm : Map.of();
    } catch (IOException | RuntimeException e) {
      // A malformed manifest means "no declared ceiling", not a 500. The
      // manifest-schema CI job is what catches a broken one.
      log.debug("resources({}) failed: {}", pkg, e.getMessage());
      return Map.of();
    }
  }

  /** Crude but sufficient read of the tiny JSON blob this service writes. */
  private Object override(String pkg, String field) {
    return settings.get(KEY_PREFIX + pkg)
        .map(json -> {
          var m = java.util.regex.Pattern
              .compile("\"" + field + "\"\\s*:\\s*([0-9.]+)").matcher(json);
          return m.find() ? (Object) m.group(1) : null;
        })
        .orElse(null);
  }

  public static Integer intOrNull(Object raw) {
    if (raw instanceof Number n) return n.intValue();
    if (raw instanceof String s) {
      try {
        return (int) Double.parseDouble(s.trim());
      } catch (NumberFormatException ignore) {
        return null;
      }
    }
    return null;
  }

  public static Double numberOrNull(Object raw) {
    if (raw instanceof Number n) return n.doubleValue();
    if (raw instanceof String s) {
      try {
        return Double.parseDouble(s.trim());
      } catch (NumberFormatException ignore) {
        return null;
      }
    }
    return null;
  }
}
