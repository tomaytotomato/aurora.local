package com.tomaytotomato.aurora.domain;

import java.util.List;

/**
 * SnapRAID parity freshness.
 *
 * @param configured    derived from whether any disk in {@code disks.json}
 *                       carries the {@code parity} role, not from whether
 *                       {@code parity.json} happens to exist — a box can be
 *                       configured for parity and simply never have synced
 *                       yet, which is the {@code never} state below, not
 *                       "not configured"
 * @param parityDiskIds derived the same way, for the same reason
 * @param lastSyncState {@code ok | failed | aborted | never} — {@code aborted}
 *                       is the snapraid-runner's deletion guard refusing to
 *                       sync, which is deliberate and distinct from a fault
 */
public record Parity(
    boolean configured,
    List<String> parityDiskIds,
    String lastSyncAt,
    String lastSyncState,
    String lastScrubAt,
    Integer pendingChanges,
    Integer deletedSinceSync,
    Integer deletionThreshold,
    int stalenessWarnDays
) {}
