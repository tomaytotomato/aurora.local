package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.security.UnpinnedImageTagsRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
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
 */
@Service
public class HardeningService {

  private static final Logger log = LoggerFactory.getLogger(HardeningService.class);

  private static final Pattern SOCKET_LINE =
      Pattern.compile("^\\s*-\\s*[\"']?(/var/run/docker\\.sock:[^\\s\"']+)", Pattern.MULTILINE);

  /** How sops marks a file it has encrypted. Either marker is conclusive. */
  private static final Pattern SOPS_MARKER = Pattern.compile("(?m)^sops:|ENC\\[AES256_GCM");

  private final ComposeScanner compose;

  public HardeningService(ComposeScanner compose) {
    this.compose = compose;
  }

  public Map<String, Object> state() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("pinning", pinning());
    out.put("secrets", secrets());
    out.put("dockerSocket", dockerSocket());
    return out;
  }

  // ------------------------------------------------------------------

  private Map<String, Object> pinning() {
    int total = 0;
    int pinned = 0;
    List<String> unpinned = new ArrayList<>();

    for (var ref : compose.allImages()) {
      total++;
      if (UnpinnedImageTagsRule.classify(ref.image()) == UnpinnedImageTagsRule.Verdict.PINNED) {
        pinned++;
      } else if (!unpinned.contains(ref.image())) {
        unpinned.add(ref.image());
      }
    }

    // scripts/pin.sh writes one pins.env PER PACKAGE
    // (packages/<pkg>/pins.env — see its header and the write at line
    // 149). This used to look for a single file at the repo root, which
    // nothing has ever written, so the flag was false on every box that
    // exists — including one where pin.sh apply had just run. The
    // frontend turns it into a Security-page finding, so the page was
    // stating something untrue about the box.
    List<Path> pinsFiles = pinsFiles();
    boolean pinsFileExists = !pinsFiles.isEmpty();

    Map<String, Object> m = new LinkedHashMap<>();
    m.put("total", total);
    m.put("pinned", pinned);
    m.put("unpinned", unpinned);
    m.put("pinsFileExists", pinsFileExists);
    // Newest wins: "when were the pins last refreshed" is the question
    // the timestamp is asked to answer, and pin.sh rewrites only the
    // packages whose images drifted.
    m.put("generatedAt", pinsFiles.stream()
        .map(HardeningService::lastModified)
        .filter(java.util.Objects::nonNull)
        .max(String::compareTo)
        .orElse(null));
    return m;
  }

  /** Every {@code packages/<pkg>/pins.env} in the repo. */
  private List<Path> pinsFiles() {
    Path packages = compose.repo().resolve("packages");
    if (!Files.isDirectory(packages)) return List.of();
    try (var dirs = Files.list(packages)) {
      return dirs.filter(Files::isDirectory)
          .map(d -> d.resolve("pins.env"))
          .filter(Files::isRegularFile)
          .sorted()
          .toList();
    } catch (IOException e) {
      log.debug("could not list packages for pins.env: {}", e.getMessage());
      return List.of();
    }
  }

  private Map<String, Object> secrets() {
    int envFiles = 0;
    int encrypted = 0;
    boolean sawSops = false;

    Path packages = compose.repo().resolve("packages");
    if (Files.isDirectory(packages)) {
      try (Stream<Path> dirs = Files.list(packages)) {
        for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
          Path env = dir.resolve(".env");
          if (!Files.isRegularFile(env)) continue;
          envFiles++;
          if (SOPS_MARKER.matcher(compose.read(env)).find()) {
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

  private Map<String, Object> dockerSocket() {
    List<String> exposed = new ArrayList<>();
    boolean writable = false;
    boolean proxyPresent = false;

    for (Path file : compose.composeFiles()) {
      String body = compose.read(file);
      // A socket proxy in the tree is what "done" looks like; the service
      // name is the convention the plan settled on.
      if (body.contains("docker-socket-proxy")) {
        proxyPresent = true;
      }
      Matcher m = SOCKET_LINE.matcher(body);
      while (m.find()) {
        String mount = m.group(1);
        String owner = ComposeScanner.ownerOf(file);
        if (!exposed.contains(owner)) exposed.add(owner);
        // Absent a mode suffix docker defaults to read-write, which is the
        // dangerous case, so treat "unspecified" as writable rather than
        // giving it the benefit of the doubt.
        if (!mount.endsWith(":ro")) writable = true;
      }
    }

    Map<String, Object> m = new LinkedHashMap<>();
    // A proxy sitting alongside a container that kept its own raw mount
    // has not solved anything, so both conditions have to hold.
    m.put("proxied", proxyPresent && exposed.isEmpty());
    m.put("exposedContainers", exposed);
    m.put("writable", writable);
    return m;
  }

  private static String lastModified(Path p) {
    try {
      return Files.getLastModifiedTime(p).toInstant().toString();
    } catch (IOException e) {
      return null;
    }
  }
}
