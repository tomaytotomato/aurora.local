package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import org.springframework.stereotype.Service;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates caller-supplied package names before anything writes them to disk
 * (chiefly {@code .state.yml} via {@link OnboardingService#setEnabledPackages}).
 *
 * <p>Two independent checks:
 * <ol>
 *   <li>Shape — each name matches {@code ^[a-z][a-z0-9-]*$}. Rejects path
 *       traversal, uppercase, empty strings, leading digits/hyphens.</li>
 *   <li>Existence — each name corresponds to a real directory under
 *       {@code packages/}. Rejects unknown packages (typos, malicious input,
 *       stale UI state).</li>
 * </ol>
 *
 * <p>Also enforces: non-null list, non-empty list, no duplicates. Fails
 * all-or-nothing — either the whole list is accepted or nothing is written.
 *
 * <p>The set of valid packages is re-read from the filesystem on each call.
 * This is cheap (one directory scan, ~15 entries) and keeps the validator
 * honest across on-disk package additions/removals without a restart.
 */
@Service
public class PackageNameValidator {

  /** Names Aurora's own dashboard package excludes itself from the caller-selectable set. */
  private static final Set<String> RESERVED = Set.of("dashboard");

  static final Pattern SHAPE = Pattern.compile("^[a-z][a-z0-9-]*$");

  private final AuroraProperties props;

  public PackageNameValidator(AuroraProperties props) {
    this.props = props;
  }

  /**
   * @param names caller-supplied list.
   * @throws InvalidPackageNamesException when validation fails. The exception
   *   carries structured lists of {@code invalid} (shape violations) and
   *   {@code unknown} (shape-valid but not on disk) so the controller can
   *   render the shaped 400 body without re-parsing a message.
   */
  public void validate(List<String> names) {
    if (names == null) {
      throw new InvalidPackageNamesException(
          "enabled_packages must be a JSON array of package names",
          List.of(), List.of());
    }
    if (names.isEmpty()) {
      throw new InvalidPackageNamesException(
          "enabled_packages must contain at least one package",
          List.of(), List.of());
    }

    List<String> invalid = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    List<String> duplicates = new ArrayList<>();
    for (String raw : names) {
      if (raw == null || !SHAPE.matcher(raw).matches() || RESERVED.contains(raw)) {
        invalid.add(String.valueOf(raw));
        continue;
      }
      if (!seen.add(raw)) duplicates.add(raw);
    }
    if (!duplicates.isEmpty()) {
      throw new InvalidPackageNamesException(
          "enabled_packages contains duplicate entries: " + duplicates,
          List.of(), List.of());
    }
    if (!invalid.isEmpty()) {
      throw new InvalidPackageNamesException(
          "one or more package names are malformed",
          invalid, List.of());
    }

    Set<String> known = listPackagesOnDisk();
    List<String> unknown = new ArrayList<>();
    for (String n : names) if (!known.contains(n)) unknown.add(n);
    if (!unknown.isEmpty()) {
      throw new InvalidPackageNamesException(
          "one or more packages do not exist on this box",
          List.of(), unknown);
    }
  }

  /** Scan {@code packages/} once. Excludes the dashboard itself and hidden/template dirs. */
  private Set<String> listPackagesOnDisk() {
    Set<String> out = new HashSet<>();
    Path root = Path.of(props.repoPath()).resolve("packages");
    if (!Files.isDirectory(root)) return out;
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(root, Files::isDirectory)) {
      for (Path d : ds) {
        String n = d.getFileName().toString();
        if (n.startsWith(".") || n.startsWith("_")) continue;
        if (RESERVED.contains(n)) continue;
        out.add(n);
      }
    } catch (java.io.IOException e) {
      // Fail closed: an unreadable packages/ dir cannot validate anything.
      throw new IllegalStateException("cannot scan packages directory: " + root, e);
    }
    return out;
  }

  /**
   * Thrown when {@link #validate(List)} rejects the input. Carries structured
   * lists so the controller can render {@code {invalid:[...],unknown:[...]}}
   * without re-parsing a message string.
   */
  public static class InvalidPackageNamesException extends IllegalArgumentException {
    public final List<String> invalid;
    public final List<String> unknown;

    public InvalidPackageNamesException(String message,
                                        List<String> invalid,
                                        List<String> unknown) {
      super(message);
      this.invalid = List.copyOf(invalid);
      this.unknown = List.copyOf(unknown);
    }
  }
}
