package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PackageNameValidator}.
 *
 * <p>Covers shape rejection (path traversal, uppercase, empty, reserved),
 * existence rejection (unknown package), duplicate rejection, empty-list
 * rejection, null-list rejection, and mixed-input all-or-nothing behaviour.
 */
class PackageNameValidatorTests {

  private static PackageNameValidator make(Path repo, String... packageDirs) throws Exception {
    Files.createDirectories(repo.resolve("packages"));
    for (String d : packageDirs) {
      Files.createDirectories(repo.resolve("packages").resolve(d));
    }
    AuroraProperties props = new AuroraProperties(
        repo.toString(),
        "/host/proc",
        null,
        new AuroraProperties.Docker("unix:///var/run/docker.sock"));
    return new PackageNameValidator(props);
  }

  @Test
  void acceptsValidListOfKnownPackages(@TempDir Path repo) throws Exception {
    var v = make(repo, "core", "media", "privacy");
    assertDoesNotThrow(() -> v.validate(List.of("core", "media", "privacy")));
  }

  @Test
  void rejectsPathTraversal(@TempDir Path repo) throws Exception {
    var v = make(repo, "core");
    var e = assertThrows(PackageNameValidator.InvalidPackageNamesException.class,
        () -> v.validate(List.of("../etc/passwd")));
    assertTrue(e.invalid.contains("../etc/passwd"), "invalid list: " + e.invalid);
  }

  @Test
  void rejectsUppercase(@TempDir Path repo) throws Exception {
    var v = make(repo, "core");
    var e = assertThrows(PackageNameValidator.InvalidPackageNamesException.class,
        () -> v.validate(List.of("Core")));
    assertTrue(e.invalid.contains("Core"));
  }

  @Test
  void rejectsEmptyString(@TempDir Path repo) throws Exception {
    var v = make(repo, "core");
    var e = assertThrows(PackageNameValidator.InvalidPackageNamesException.class,
        () -> v.validate(List.of("")));
    assertTrue(e.invalid.contains(""));
  }

  @Test
  void rejectsUnknownPackage(@TempDir Path repo) throws Exception {
    var v = make(repo, "core", "media");
    var e = assertThrows(PackageNameValidator.InvalidPackageNamesException.class,
        () -> v.validate(List.of("core", "foo")));
    assertTrue(e.unknown.contains("foo"), "unknown list: " + e.unknown);
    assertTrue(e.invalid.isEmpty(), "shape was fine; only existence failed");
  }

  @Test
  void rejectsDuplicate(@TempDir Path repo) throws Exception {
    var v = make(repo, "core", "media");
    var e = assertThrows(PackageNameValidator.InvalidPackageNamesException.class,
        () -> v.validate(List.of("core", "core")));
    assertTrue(e.getMessage().contains("duplicate"), "msg: " + e.getMessage());
  }

  @Test
  void rejectsEmptyList(@TempDir Path repo) throws Exception {
    var v = make(repo, "core");
    var e = assertThrows(PackageNameValidator.InvalidPackageNamesException.class,
        () -> v.validate(List.of()));
    assertTrue(e.getMessage().toLowerCase().contains("at least one"),
        "msg: " + e.getMessage());
  }

  @Test
  void rejectsNullList(@TempDir Path repo) throws Exception {
    var v = make(repo, "core");
    assertThrows(PackageNameValidator.InvalidPackageNamesException.class,
        () -> v.validate(null));
  }

  @Test
  void rejectsMixedValidAndInvalidAllOrNothing(@TempDir Path repo) throws Exception {
    var v = make(repo, "core", "media");
    // "core" is valid, "../evil" and "BAD" are shape-invalid, "foo" is unknown.
    var e = assertThrows(PackageNameValidator.InvalidPackageNamesException.class,
        () -> v.validate(Arrays.asList("core", "../evil", "BAD", "foo")));
    // Shape check runs first; both malformed entries surfaced together.
    assertEquals(2, e.invalid.size(), "expected 2 shape violations, got " + e.invalid);
    assertTrue(e.invalid.contains("../evil"));
    assertTrue(e.invalid.contains("BAD"));
  }

  @Test
  void rejectsReservedDashboardName(@TempDir Path repo) throws Exception {
    var v = make(repo, "core", "dashboard");
    // Even if the dashboard dir exists on disk, callers can't select it.
    var e = assertThrows(PackageNameValidator.InvalidPackageNamesException.class,
        () -> v.validate(List.of("dashboard")));
    assertTrue(e.invalid.contains("dashboard"), "should reject reserved name");
  }

  @Test
  void rejectsNullElement(@TempDir Path repo) throws Exception {
    var v = make(repo, "core");
    var e = assertThrows(PackageNameValidator.InvalidPackageNamesException.class,
        () -> v.validate(Arrays.asList("core", null)));
    assertTrue(e.invalid.contains("null"));
  }
}
