package com.tomaytotomato.aurora.domain;

/**
 * One row of {@code smartctl}'s attribute table, passed through close to
 * verbatim from {@code ata_smart_attributes.table[]} in the collected
 * state file.
 */
public record SmartAttribute(
    int id,
    String name,
    int value,
    int worst,
    int threshold,
    String raw,
    String failedWhen
) {}
