package com.tomaytotomato.aurora.domain;

/**
 * The Overview facts, and what drives the Home tile: is my data safe?
 *
 * <p>Every size is nullable on purpose. A repository Aurora could not
 * reach has no size, and reporting zero would read as "nothing to back
 * up" — the opposite of the truth, and exactly the reassuring lie
 * {@code docs/BACKUP_PAGE_DESIGN.md} §4 forbids.
 */
public record BackupStatus(
    String repoState,
    String repoKind,
    String repoLocation,
    boolean encrypted,
    Long totalSizeBytes,
    Long uniqueSizeBytes,
    int snapshotCount,
    String lastRunAt,
    String lastRunState,
    Long lastRunDurationMs,
    String nextRunAt,
    String generatedAt
) {
  public static final String NOT_CONFIGURED = "not-configured";
  public static final String CONNECTED = "connected";
  public static final String UNREACHABLE = "unreachable";
}
