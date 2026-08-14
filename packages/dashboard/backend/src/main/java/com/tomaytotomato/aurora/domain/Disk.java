package com.tomaytotomato.aurora.domain;

/**
 * One physical disk, as far as the dashboard can honestly tell.
 *
 * <p>Every field here is read from {@code packages/dashboard/state/disks.json},
 * which {@code host/roles/smartd} writes on a timer. The dashboard container
 * has no privileges to run {@code smartctl} itself (see
 * {@code docs/DISKS_PAGE_DESIGN.md}), so this is only ever as fresh as the
 * host's last collection pass.
 *
 * @param id                 stable id, by-id where available so it survives a re-cable
 * @param role               {@code data | parity | system | unassigned}
 * @param health              {@code passed | warning | failing | unknown} — see
 *                            {@code DisksStateParser#health} for why this is not simply
 *                            smartctl's own overall verdict
 * @param lastSelfTestResult display text only; never used to derive health
 */
public record Disk(
    String id,
    String device,
    String model,
    String serial,
    long sizeBytes,
    String role,
    String mountpoint,
    String filesystem,
    Long usedBytes,
    String health,
    Integer temperatureC,
    Integer powerOnHours,
    Integer reallocatedSectors,
    Integer pendingSectors,
    String lastSelfTestAt,
    String lastSelfTestResult
) {}
