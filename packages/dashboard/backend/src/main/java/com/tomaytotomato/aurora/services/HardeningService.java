package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.security.UnpinnedImageTagsRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Where the three outstanding hardening decisions actually stand: images
 * pinned to a digest, secrets encrypted at rest, and the Docker socket
 * behind a proxy.
 *
 * <p>All three have been agreed and recorded in the repo's plan for a
 * while and none has ever been visible on the box, which is how a
 * decision quietly becomes a thing nobody did.
 *
 * <p>This reads the repository rather than the running containers, which
 * is a deliberate difference from {@link UnpinnedImageTagsRule} and
 * {@code DockerSocketExposureRule}. Those answer "what is running right
 * now"; this answers "what would happen on the next rebuild", and the
 * compose files are the truth for that. A package that is not currently
 * enabled still has an unpinned image waiting for it.
 *
 * <p>The compose scan is line-level rather than a YAML parse, on purpose:
 * compose files are full of {@code ${VAR:-default}} interpolation that a
 * parser resolves to nothing useful, and the facts wanted here — which
 * image reference, which bind mount — are single lines.
 */
@Service
public class HardeningService {

  private static final Logger log = LoggerFactory.getLogger(HardeningService.class);

  private static final Pattern IMAGE_LINE =
      Pattern.compile("^\\s*image:\\s*[\"']?([^\\s\"']+)", Pattern.MULTILINE);

  private static final Pattern SOCKET_LINE =
      Pattern.compile("^\\s*-\\s*[\"']?(/var/run/docker\\.sock:[^\\s\"']+)", Pattern.MULTILINE);

  /** How sops marks a file it has encrypted. Either marker is conclusive. */
  private static final Pattern SOPS_MARKER = Pattern.compile("(?m)^sops:|ENC\\[AES256_GCM");

  private final AuroraProperties props;

  public HardeningService(AuroraProperties props) {
    this.props = props;
  }

  public Map<String, Object> state() {
    List<Path> composeFiles = composeFiles();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("pinning", pinning(composeFiles));
    out.put("secrets", secrets());
    out.put("dockerSocket", dockerSocket(composeFiles));
    return out;
  }

  // ------------------------------------------------------------------

  private Map<String, Object> pinning(List<Path> composeFiles) {
    int total = 0;
    int pinned = 0;
    List<String> unpinned = new ArrayList<>();

    for (Path file : composeFiles) {
      Matcher m = IMAGE_LINE.matcher(read(file));
      while (m.find()) {
        String image = m.group(1);
        total++;
        if (UnpinnedImageTagsRule.classify(image) == UnpinnedImageTagsRule.Verdict.PINNED) {
          pinned++;
        } else if (!unpinned.contains(image)) {
          unpinned.add(image);
        }
      }
    }

    Path pins = repo().resolve("pins.env");
    boolean pinsFileExists = Files.isRegularFile(pins);

    Map<String, Object> m = new LinkedHashMap<>();
    m.put("total", total);
    m.put("pinned", pinned);
    m.put("unpinned", unpinned);
    m.put("pinsFileExists", pinsFileExists);
    m.put("generatedAt", pinsFileExists ? lastModified(pins) : null);
    return m;
  }

  private Map<String, Object> secrets() {
    int envFiles = 0;
    int encrypted = 0;
    boolean sawSops = false;

    Path packages = repo().resolve("packages");
    if (Files.isDirectory(packages)) {
      try (Stream<Path> dirs = Files.list(packages)) {
        for (Path dir : dirs.filter(Files::isDirectory).toList()) {
          Path env = dir.resolve(".env");
          if (!Files.isRegularFile(env)) continue;
          envFiles++;
          if (SOPS_MARKER.matcher(read(env)).find()) {
            encrypted++;
            sawSops = true;
          }
        }
      } catch (IOException e) {
        log.debug("could not scan for .env files: {}", e.getMessage());
      }
    }

    Map<String, Object> m = new LinkedHashMap<>();
    // Only "encrypted" when there is something to encrypt and all of it is.
    // A box with no .env files at all is not a hardened box, it is an
    // empty one, and saying otherwise would be a green tick nobody earned.
    m.put("encrypted", envFiles > 0 && encrypted == envFiles);
    m.put("method", sawSops ? "sops-age" : null);
    m.put("envFiles", envFiles);
    m.put("encryptedFiles", encrypted);
    return m;
  }

  private Map<String, Object> dockerSocket(List<Path> composeFiles) {
    List<String> exposed = new ArrayList<>();
    boolean writable = false;
    boolean proxied = false;

    for (Path file : composeFiles) {
      String body = read(file);
      // A socket proxy in the tree is what "done" looks like; the service
      // name is the convention the plan settled on.
      if (body.contains("docker-socket-proxy") || body.contains("tecnativa/docker-socket-proxy")) {
        proxied = true;
      }
      Matcher m = SOCKET_LINE.matcher(body);
      while (m.find()) {
        String mount = m.group(1);
        String owner = ownerOf(file);
        if (!exposed.contains(owner)) exposed.add(owner);
        // Absent a mode suffix docker defaults to read-write, which is the
        // dangerous case, so treat "unspecified" as writable rather than
        // giving it the benefit of the doubt.
        if (!mount.endsWith(":ro")) writable = true;
      }
    }

    Map<String, Object> m = new LinkedHashMap<>();
    m.put("proxied", proxied && exposed.isEmpty());
    m.put("exposedContainers", exposed);
    m.put("writable", writable);
    return m;
  }

  // ------------------------------------------------------------------

  private Path repo() {
    return Path.of(props.repoPath());
  }

  /** {@code packages/<name>/compose.yml} for every package in the repo. */
  private List<Path> composeFiles() {
    Path packages = repo().resolve("packages");
    if (!Files.isDirectory(packages)) return List.of();
    try (Stream<Path> dirs = Files.list(packages)) {
      return dirs.filter(Files::isDirectory)
          .map(d -> d.resolve("compose.yml"))
          .filter(Files::isRegularFile)
          .sorted()
          .toList();
    } catch (IOException e) {
      log.debug("could not list packages: {}", e.getMessage());
      return List.of();
    }
  }

  /** The package a compose file belongs to, for naming what is exposed. */
  private static String ownerOf(Path composeFile) {
    Path parent = composeFile.getParent();
    return parent == null ? composeFile.toString() : parent.getFileName().toString();
  }

  private static String read(Path p) {
    try {
      return Files.readString(p, StandardCharsets.UTF_8);
    } catch (IOException e) {
      // An unreadable file is reported as empty rather than failing the
      // whole scan: one bad file should not make the page say nothing.
      log.debug("could not read {}: {}", p, e.getMessage());
      return "";
    }
  }

  private static String lastModified(Path p) {
    try {
      return Files.getLastModifiedTime(p).toInstant().toString();
    } catch (IOException e) {
      return null;
    }
  }
}
