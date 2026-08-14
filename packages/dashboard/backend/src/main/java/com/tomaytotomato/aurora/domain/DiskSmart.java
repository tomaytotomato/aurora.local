package com.tomaytotomato.aurora.domain;

import java.util.List;

/**
 * Full SMART attribute table for one disk.
 *
 * @param collectedAt when the host last ran {@code smartctl} for this
 *                     disk — the freshness the honesty rule in
 *                     {@code DISKS_PAGE_DESIGN.md} asks the UI to surface
 *                     rather than hide
 */
public record DiskSmart(
    String diskId,
    boolean supported,
    String overall,
    List<SmartAttribute> attributes,
    String collectedAt
) {}
