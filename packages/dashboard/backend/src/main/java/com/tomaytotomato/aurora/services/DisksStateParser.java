package com.tomaytotomato.aurora.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.tomaytotomato.aurora.domain.Disk;
import com.tomaytotomato.aurora.domain.DiskSmart;
import com.tomaytotomato.aurora.domain.Parity;
import com.tomaytotomato.aurora.domain.Pool;
import com.tomaytotomato.aurora.domain.PoolBranch;
import com.tomaytotomato.aurora.domain.SmartAttribute;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the two JSON state files {@code host/roles/smartd} and
 * {@code host/roles/snapraid} write into the domain shapes the
 * {@code /disks} endpoints serve.
 *
 * <p>This is deliberately a plain class with no Spring or filesystem
 * dependency of its own — {@link DisksService} owns finding the files and
 * handles IO/missing-file/malformed-JSON; this class only ever sees a
 * {@link JsonNode} it has already been handed, which is what makes it
 * possible to unit test against realistic {@code smartctl -j} output
 * without a Spring context.
 *
 * <p><b>The regression this exists to prevent:</b> {@code diskAttention} in
 * the frontend once flagged every healthy drive, because smartctl's own
 * happy self-test result is the string "Completed without error" and an
 * earlier check searched it for the word "error". Every method here that
 * decides pass/fail reads a structured boolean or count —
 * {@code smart_status.passed}, an attribute's raw sector count — and never
 * pattern-matches self-test text. {@code lastSelfTestResult} is carried
 * through as plain display copy and is never inspected by this class.
 */
final class DisksStateParser {

  private DisksStateParser() {}

  // ------------------------------------------------------------------
  // disks.json
  // ------------------------------------------------------------------

  static List<Disk> parseDisks(JsonNode root) {
    List<Disk> out = new ArrayList<>();
    if (root == null || root.isMissingNode()) return out;
    for (JsonNode d : root.path("disks")) {
      out.add(parseDisk(d));
    }
    return out;
  }

  static Disk parseDisk(JsonNode d) {
    JsonNode smart = d.path("smart");
    boolean supported = smart.path("supported").asBoolean(false);
    Integer reallocated = attributeRawValue(smart, 5);   // Reallocated_Sector_Ct
    Integer pending = attributeRawValue(smart, 197);      // Current_Pending_Sector
    Integer powerOnHours = optInt(smart.path("power_on_time").path("hours"));
    Integer temperatureC = optInt(smart.path("temperature").path("current"));
    JsonNode lastTest = firstSelfTestEntry(smart);
    String lastSelfTestAt = lastTest == null ? null : optText(lastTest.path("computed_at"));
    String lastSelfTestResult = lastTest == null ? null : optText(lastTest.path("status").path("string"));
    String role = emptyToNull(optText(d.path("role")));

    return new Disk(
        optText(d.path("id")),
        optText(d.path("device")),
        optText(d.path("model")),
        optText(d.path("serial")),
        d.path("sizeBytes").asLong(0L),
        role == null ? "unassigned" : role,
        optText(d.path("mountpoint")),
        optText(d.path("filesystem")),
        d.hasNonNull("usedBytes") ? d.path("usedBytes").asLong() : null,
        health(supported, smart, reallocated, pending),
        temperatureC,
        powerOnHours,
        reallocated,
        pending,
        lastSelfTestAt,
        lastSelfTestResult
    );
  }

  static DiskSmart parseSmart(JsonNode disk, String diskId, String collectedAt) {
    JsonNode smart = disk.path("smart");
    boolean supported = smart.path("supported").asBoolean(false);
    Integer reallocated = attributeRawValue(smart, 5);
    Integer pending = attributeRawValue(smart, 197);
    List<SmartAttribute> attributes = new ArrayList<>();
    for (JsonNode attr : smart.path("ata_smart_attributes").path("table")) {
      attributes.add(new SmartAttribute(
          attr.path("id").asInt(0),
          optText(attr.path("name")),
          attr.path("value").asInt(0),
          attr.path("worst").asInt(0),
          attr.path("thresh").asInt(0),
          rawString(attr.path("raw")),
          optText(attr.path("when_failed"))
      ));
    }
    return new DiskSmart(diskId, supported, health(supported, smart, reallocated, pending), attributes, collectedAt);
  }

  /**
   * The one piece of logic every honesty rule in
   * {@code DISKS_PAGE_DESIGN.md} §4 boils down to: {@code unknown} when the
   * drive exposes nothing, {@code failing} only on smartctl's own
   * structured verdict, {@code warning} the moment either reallocated or
   * pending sectors are non-zero even though the overall verdict still
   * says passed, {@code passed} only when none of that is true.
   *
   * <p>Nothing here reads {@code lastSelfTestResult} or any other free
   * text — see the class javadoc.
   */
  static String health(boolean supported, JsonNode smart, Integer reallocated, Integer pending) {
    if (!supported) return "unknown";
    JsonNode status = smart.path("smart_status");
    if (!status.hasNonNull("passed")) {
      // Supported but no structured verdict at all — an honest "unknown"
      // beats guessing a pass from silence.
      return "unknown";
    }
    boolean passed = status.path("passed").asBoolean(false);
    if (!passed) return "failing";
    if ((reallocated != null && reallocated > 0) || (pending != null && pending > 0)) return "warning";
    return "passed";
  }

  private static Integer attributeRawValue(JsonNode smart, int attrId) {
    for (JsonNode attr : smart.path("ata_smart_attributes").path("table")) {
      if (attr.path("id").asInt(-1) == attrId) {
        JsonNode raw = attr.path("raw");
        if (raw.hasNonNull("value")) return raw.path("value").asInt();
        String s = optText(raw.path("string"));
        if (s != null) {
          try {
            return Integer.parseInt(s.trim().split("\\s+")[0]);
          } catch (NumberFormatException e) {
            return null;
          }
        }
        return null;
      }
    }
    return null;
  }

  private static String rawString(JsonNode raw) {
    String s = optText(raw.path("string"));
    if (s != null) return s;
    if (raw.hasNonNull("value")) return String.valueOf(raw.path("value").asLong());
    return "";
  }

  /**
   * Most recent self-test log entry. smartctl's own JSON orders the
   * standard self-test table newest-first, so index 0 is "most recent" —
   * this does not re-sort or otherwise second-guess that ordering.
   */
  private static JsonNode firstSelfTestEntry(JsonNode smart) {
    JsonNode table = smart.path("ata_smart_self_test_log").path("standard").path("table");
    if (table.isArray() && !table.isEmpty()) return table.get(0);
    return null;
  }

  private static Integer optInt(JsonNode n) {
    return n.isMissingNode() || n.isNull() ? null : n.asInt();
  }

  private static String optText(JsonNode n) {
    return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
  }

  private static String emptyToNull(String s) {
    return (s == null || s.isBlank()) ? null : s;
  }

  // ------------------------------------------------------------------
  // Pool — derived from disks.json, not stored separately
  // ------------------------------------------------------------------

  static Pool parsePool(JsonNode root, List<Disk> disks) {
    JsonNode pool = root == null ? null : root.path("pool");
    boolean configured = pool != null && pool.path("configured").asBoolean(false);
    if (!configured) {
      return new Pool(false, null, null, null, List.of(), null, null);
    }
    List<PoolBranch> branches = new ArrayList<>();
    long total = 0L;
    long used = 0L;
    for (Disk d : disks) {
      if (!"data".equals(d.role())) continue;
      long branchUsed = d.usedBytes() == null ? 0L : d.usedBytes();
      branches.add(new PoolBranch(d.id(), d.mountpoint(), d.sizeBytes(), branchUsed));
      total += d.sizeBytes();
      used += branchUsed;
    }
    return new Pool(
        true,
        optText(pool.path("mountpoint")),
        branches.isEmpty() ? null : total,
        branches.isEmpty() ? null : used,
        branches,
        optText(pool.path("createPolicy")),
        pool.hasNonNull("minFreeBytes") ? pool.path("minFreeBytes").asLong() : null
    );
  }

  // ------------------------------------------------------------------
  // parity.json — timing/counts from the file, identity from disks.json
  // ------------------------------------------------------------------

  static Parity parseParity(JsonNode root, List<Disk> disks, int defaultStalenessWarnDays) {
    List<String> parityDiskIds = disks.stream()
        .filter(d -> "parity".equals(d.role()))
        .map(Disk::id)
        .toList();
    boolean configured = !parityDiskIds.isEmpty();

    if (root == null || root.isMissingNode()) {
      return new Parity(configured, parityDiskIds, null, "never", null, null, null, null,
          defaultStalenessWarnDays);
    }

    String lastSyncState = emptyToNull(optText(root.path("lastSyncState")));
    return new Parity(
        configured,
        parityDiskIds,
        optText(root.path("lastSyncAt")),
        lastSyncState == null ? "never" : lastSyncState,
        optText(root.path("lastScrubAt")),
        root.hasNonNull("pendingChanges") ? root.path("pendingChanges").asInt() : null,
        root.hasNonNull("deletedSinceSync") ? root.path("deletedSinceSync").asInt() : null,
        root.hasNonNull("deletionThreshold") ? root.path("deletionThreshold").asInt() : null,
        root.hasNonNull("stalenessWarnDays") ? root.path("stalenessWarnDays").asInt() : defaultStalenessWarnDays
    );
  }
}
