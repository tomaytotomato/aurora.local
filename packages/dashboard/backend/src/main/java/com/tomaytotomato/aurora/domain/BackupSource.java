package com.tomaytotomato.aurora.domain;

import java.util.List;

/**
 * One protected path, as Aurora understands it.
 *
 * <p>Sources come from what the packages <em>declare</em> in their
 * {@code backup:} blocks, joined onto what Kopia has actually
 * snapshotted — not the other way round. A path a package claims and the
 * repository has never heard of is precisely the box that believes it is
 * backed up and is not, and listing only what Kopia knows would hide it.
 */
public record BackupSource(
    String id,
    String path,
    String pkg,
    boolean enabled,
    String lastSnapshotAt,
    String lastSnapshotState,
    Long sizeBytes,
    Integer fileCount,
    List<BackupAction> beforeActions,
    boolean needsConsistencyAction
) {

  /**
   * {@code package} is a Java keyword, so the record component is
   * {@code pkg} and this renames it on the wire to match
   * {@code openapi.yaml}'s {@code BackupSource.package}.
   */
  @com.fasterxml.jackson.annotation.JsonProperty("package")
  public String packageName() {
    return pkg;
  }

  /** Kept off the wire; {@link #packageName()} carries it instead. */
  @com.fasterxml.jackson.annotation.JsonIgnore
  @Override
  public String pkg() {
    return pkg;
  }
}
