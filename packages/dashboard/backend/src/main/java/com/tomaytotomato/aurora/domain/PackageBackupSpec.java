package com.tomaytotomato.aurora.domain;

import java.util.List;

/**
 * A package's {@code backup:} manifest block: which paths it owns, and
 * what must run first for a snapshot of them to be restorable.
 *
 * <p>Serving this matters more than it looks. The backup page can only be
 * honest about what is covered if it knows what each package claims, and
 * a box that believes it is backed up when nothing has declared its paths
 * is worse than one that admits it is not.
 */
public record PackageBackupSpec(
    List<String> paths,
    List<BackupAction> before
) {

  /**
   * Reads the block as parsed from YAML. Returns {@code null} — not an
   * empty spec — when the package declares no {@code backup:} block at
   * all, because "this package has not said anything about backup" and
   * "this package owns no paths" are different facts and the page renders
   * them differently.
   */
  @SuppressWarnings("unchecked")
  public static PackageBackupSpec fromManifest(Object raw) {
    if (!(raw instanceof java.util.Map<?, ?> m)) return null;

    List<String> paths = m.get("paths") instanceof List<?> list
        ? list.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList()
        : List.of();

    List<BackupAction> before = List.of();
    if (m.get("before") instanceof List<?> list) {
      before = list.stream()
          .filter(o -> o instanceof java.util.Map)
          .map(o -> (java.util.Map<String, Object>) o)
          .map(a -> new BackupAction(
              a.get("kind") == null ? null : a.get("kind").toString(),
              a.get("description") == null ? null : a.get("description").toString(),
              a.get("container") == null ? null : a.get("container").toString()))
          .toList();
    }

    return new PackageBackupSpec(paths, before);
  }
}
