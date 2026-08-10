package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads facts out of the packages' compose files.
 *
 * <p>Line-level rather than a YAML parse, deliberately. Compose files are
 * full of {@code ${VAR:-default}} interpolation that a parser resolves to
 * nothing useful, and everything wanted here — which image, which bind
 * mount — is a single line. A parser would be more correct in principle
 * and less correct in practice.
 *
 * <p>Shared by {@link HardeningService}, which asks what is pinned, and
 * {@link UpdatesService}, which asks what could move. One definition of
 * "the images this package uses" rather than two that drift.
 */
@Service
public class ComposeScanner {

  private static final Logger log = LoggerFactory.getLogger(ComposeScanner.class);

  private static final Pattern IMAGE_LINE =
      Pattern.compile("^\\s*image:\\s*[\"']?([^\\s\"']+)", Pattern.MULTILINE);

  /** An image reference and the package whose compose file declares it. */
  public record ImageRef(String pkg, String image) {
  }

  private final AuroraProperties props;

  public ComposeScanner(AuroraProperties props) {
    this.props = props;
  }

  public Path repo() {
    return Path.of(props.repoPath());
  }

  /** {@code packages/<name>/compose.yml} for every package in the repo, sorted. */
  public List<Path> composeFiles() {
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

  /** Package names that have a compose file, sorted. */
  public List<String> packageNames() {
    return composeFiles().stream().map(ComposeScanner::ownerOf).toList();
  }

  /** Every image reference in the repo, in package order. */
  public List<ImageRef> allImages() {
    List<ImageRef> out = new ArrayList<>();
    for (Path file : composeFiles()) {
      String owner = ownerOf(file);
      for (String image : imagesIn(read(file))) {
        out.add(new ImageRef(owner, image));
      }
    }
    return out;
  }

  /** Image references declared by one package, in file order, deduplicated. */
  public List<String> imagesFor(String pkg) {
    Path file = repo().resolve("packages").resolve(pkg).resolve("compose.yml");
    if (!Files.isRegularFile(file)) return List.of();
    return imagesIn(read(file));
  }

  private static List<String> imagesIn(String body) {
    List<String> out = new ArrayList<>();
    Matcher m = IMAGE_LINE.matcher(body);
    while (m.find()) {
      String image = m.group(1);
      if (!out.contains(image)) out.add(image);
    }
    return out;
  }

  /** The package a compose file belongs to. */
  public static String ownerOf(Path composeFile) {
    Path parent = composeFile.getParent();
    return parent == null ? composeFile.toString() : parent.getFileName().toString();
  }

  /**
   * File contents, or empty when unreadable. One bad file should degrade
   * a single package's answer, not make the whole scan fail.
   */
  public String read(Path p) {
    try {
      return Files.readString(p, StandardCharsets.UTF_8);
    } catch (IOException e) {
      log.debug("could not read {}: {}", p, e.getMessage());
      return "";
    }
  }
}
