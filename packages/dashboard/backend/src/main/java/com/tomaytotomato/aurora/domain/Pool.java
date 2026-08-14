package com.tomaytotomato.aurora.domain;

import java.util.List;

/**
 * The mergerfs pool and its branches.
 *
 * <p>{@code branches}, {@code totalBytes} and {@code usedBytes} are derived
 * from the {@code data}-role disks in {@code disks.json} rather than stored
 * separately, so there is exactly one source of truth for disk capacity.
 *
 * @param configured false on a single-disk box, which is a valid setup
 *                   rather than an error
 */
public record Pool(
    boolean configured,
    String mountpoint,
    Long totalBytes,
    Long usedBytes,
    List<PoolBranch> branches,
    String createPolicy,
    Long minFreeBytes
) {}
