package com.tomaytotomato.aurora.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomaytotomato.aurora.domain.Disk;
import com.tomaytotomato.aurora.domain.DiskSmart;
import com.tomaytotomato.aurora.domain.Parity;
import com.tomaytotomato.aurora.domain.Pool;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DisksStateParser} against JSON shaped like real {@code smartctl -j}
 * output, not an idealised one — the field names below
 * ({@code smart_status}, {@code ata_smart_attributes.table[]},
 * {@code power_on_time}, {@code ata_smart_self_test_log}) match the real
 * tool, because the regression this class exists to prevent
 * ({@code diskAttention} once flagged every healthy drive by searching
 * smartctl's own "Completed without error" for the word "error") only
 * shows up if the fixtures look like what smartctl actually emits.
 */
class DisksStateParserTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode json(String s) {
    try {
      return MAPPER.readTree(s);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** One healthy ATA disk, self-test text containing the word "error". */
  private static final String HEALTHY_DISK = """
      {
        "id": "ata-Samsung_SSD_870_EVO_500GB_S5Y7NJ0R",
        "device": "/dev/sda",
        "sizeBytes": 500107862016,
        "role": "system",
        "mountpoint": "/",
        "filesystem": "ext4",
        "usedBytes": 229920788480,
        "smart": {
          "supported": true,
          "model_name": "Samsung SSD 870 EVO 500GB",
          "serial_number": "S5Y7NJ0R203514",
          "smart_status": {"passed": true},
          "power_on_time": {"hours": 14602},
          "temperature": {"current": 34},
          "ata_smart_attributes": {"table": [
            {"id": 5, "name": "Reallocated_Sector_Ct", "value": 200, "worst": 200, "thresh": 140, "raw": {"value": 0, "string": "0"}},
            {"id": 197, "name": "Current_Pending_Sector", "value": 200, "worst": 200, "thresh": 0, "raw": {"value": 0, "string": "0"}}
          ]},
          "ata_smart_self_test_log": {"standard": {"table": [
            {"type": {"string": "Short offline"}, "status": {"string": "Completed without error", "passed": true}, "lifetime_hours": 14580, "computed_at": "2026-08-10T02:00:00Z"}
          ]}}
        }
      }
      """;

  /** The named regression case: 3 reallocated + 1 pending, smart_status still passed. */
  private static final String REALLOCATING_DISK = """
      {
        "id": "ata-WDC_WD80EFZX-68UW8N0_VKHA1B2C",
        "device": "/dev/sdb",
        "sizeBytes": 8001563222016,
        "role": "data",
        "mountpoint": "/mnt/disk1",
        "filesystem": "ext4",
        "usedBytes": 5263670148096,
        "smart": {
          "supported": true,
          "model_name": "WDC WD80EFZX-68UW8N0",
          "serial_number": "VKHA1B2C",
          "smart_status": {"passed": true},
          "power_on_time": {"hours": 31204},
          "temperature": {"current": 38},
          "ata_smart_attributes": {"table": [
            {"id": 5, "name": "Reallocated_Sector_Ct", "value": 198, "worst": 198, "thresh": 140, "raw": {"value": 3, "string": "3"}},
            {"id": 197, "name": "Current_Pending_Sector", "value": 199, "worst": 200, "thresh": 0, "raw": {"value": 1, "string": "1"}}
          ]},
          "ata_smart_self_test_log": {"standard": {"table": [
            {"type": {"string": "Short offline"}, "status": {"string": "Completed without error", "passed": true}, "lifetime_hours": 31180, "computed_at": "2026-08-13T17:50:45Z"}
          ]}}
        }
      }
      """;

  /** smartctl's own structured verdict says the drive is failing. */
  private static final String FAILING_DISK = """
      {
        "id": "ata-TOSHIBA_MG08ACA16TE_91K0A004FVGG",
        "device": "/dev/sdd",
        "sizeBytes": 16000900661248,
        "role": "parity",
        "mountpoint": "/mnt/parity1",
        "filesystem": "ext4",
        "usedBytes": 8686243348480,
        "smart": {
          "supported": true,
          "smart_status": {"passed": false},
          "power_on_time": {"hours": 9140},
          "temperature": {"current": 39},
          "ata_smart_attributes": {"table": [
            {"id": 5, "name": "Reallocated_Sector_Ct", "value": 100, "worst": 100, "thresh": 140, "raw": {"value": 40, "string": "40"}}
          ]},
          "ata_smart_self_test_log": {"standard": {"table": [
            {"type": {"string": "Short offline"}, "status": {"string": "Completed: read failure", "passed": false}, "lifetime_hours": 9100}
          ]}}
        }
      }
      """;

  /** A USB enclosure: no SMART passthrough at all. */
  private static final String UNSUPPORTED_DISK = """
      {
        "id": "usb-Seagate_Expansion_HDD_NA9K2R1P",
        "device": "/dev/sde",
        "sizeBytes": 2000398934016,
        "role": "unassigned",
        "mountpoint": null,
        "filesystem": null,
        "usedBytes": null,
        "smart": {"supported": false}
      }
      """;

  @Nested
  class Health {

    @Test
    void passed_when_smart_status_passed_and_no_bad_sectors() {
      Disk d = DisksStateParser.parseDisk(json(HEALTHY_DISK));
      assertThat(d.health()).isEqualTo("passed");
    }

    @Test
    void warning_when_reallocated_sectors_present_even_though_smart_status_passed() {
      Disk d = DisksStateParser.parseDisk(json(REALLOCATING_DISK));
      assertThat(d.health()).isEqualTo("warning");
      assertThat(d.reallocatedSectors()).isEqualTo(3);
      assertThat(d.pendingSectors()).isEqualTo(1);
    }

    @Test
    void failing_when_smart_status_passed_is_false() {
      Disk d = DisksStateParser.parseDisk(json(FAILING_DISK));
      assertThat(d.health()).isEqualTo("failing");
    }

    @Test
    void unknown_when_smart_is_not_supported() {
      Disk d = DisksStateParser.parseDisk(json(UNSUPPORTED_DISK));
      assertThat(d.health()).isEqualTo("unknown");
    }

    @Test
    void unknown_when_supported_but_no_structured_verdict_at_all() {
      String noVerdict = """
          {"id": "x", "device": "/dev/sdz", "sizeBytes": 1, "role": "unassigned",
           "smart": {"supported": true}}
          """;
      Disk d = DisksStateParser.parseDisk(json(noVerdict));
      assertThat(d.health()).isEqualTo("unknown");
    }

    /**
     * The regression itself: "Completed without error" and "Completed:
     * read failure" both contain words a naive substring search would
     * flag, in opposite directions. Health must not move because of
     * either — only {@code smart_status.passed} and the sector counts
     * decide it.
     */
    @Test
    void self_test_text_never_influences_health() {
      Disk healthyWithScaryText = DisksStateParser.parseDisk(json(HEALTHY_DISK));
      assertThat(healthyWithScaryText.lastSelfTestResult()).contains("error");
      assertThat(healthyWithScaryText.health()).isEqualTo("passed");

      Disk failingWithCleanSoundingId = DisksStateParser.parseDisk(json(FAILING_DISK));
      assertThat(failingWithCleanSoundingId.health()).isEqualTo("failing");
    }
  }

  @Nested
  class DiskFields {

    @Test
    void reads_identity_and_capacity_fields() {
      Disk d = DisksStateParser.parseDisk(json(REALLOCATING_DISK));
      assertThat(d.id()).isEqualTo("ata-WDC_WD80EFZX-68UW8N0_VKHA1B2C");
      assertThat(d.device()).isEqualTo("/dev/sdb");
      assertThat(d.role()).isEqualTo("data");
      assertThat(d.mountpoint()).isEqualTo("/mnt/disk1");
      assertThat(d.sizeBytes()).isEqualTo(8001563222016L);
      assertThat(d.usedBytes()).isEqualTo(5263670148096L);
      assertThat(d.temperatureC()).isEqualTo(38);
      assertThat(d.powerOnHours()).isEqualTo(31204);
    }

    @Test
    void self_test_timestamp_and_result_are_read_from_the_computed_at_field() {
      Disk d = DisksStateParser.parseDisk(json(REALLOCATING_DISK));
      assertThat(d.lastSelfTestAt()).isEqualTo("2026-08-13T17:50:45Z");
      assertThat(d.lastSelfTestResult()).isEqualTo("Completed without error");
    }

    @Test
    void unsupported_disk_has_null_smart_fields_but_still_appears() {
      Disk d = DisksStateParser.parseDisk(json(UNSUPPORTED_DISK));
      assertThat(d.reallocatedSectors()).isNull();
      assertThat(d.pendingSectors()).isNull();
      assertThat(d.temperatureC()).isNull();
      assertThat(d.lastSelfTestAt()).isNull();
      assertThat(d.lastSelfTestResult()).isNull();
    }

    @Test
    void missing_role_defaults_to_unassigned() {
      String noRole = """
          {"id": "x", "device": "/dev/sdz", "sizeBytes": 1, "smart": {"supported": false}}
          """;
      Disk d = DisksStateParser.parseDisk(json(noRole));
      assertThat(d.role()).isEqualTo("unassigned");
    }
  }

  @Nested
  class WholeDiskList {

    @Test
    void parses_every_disk_in_the_array() {
      String root = "{\"disks\": [" + HEALTHY_DISK + "," + REALLOCATING_DISK + "]}";
      List<Disk> disks = DisksStateParser.parseDisks(json(root));
      assertThat(disks).hasSize(2);
      assertThat(disks).extracting(Disk::id)
          .containsExactly("ata-Samsung_SSD_870_EVO_500GB_S5Y7NJ0R", "ata-WDC_WD80EFZX-68UW8N0_VKHA1B2C");
    }

    @Test
    void missing_root_produces_an_empty_list() {
      assertThat(DisksStateParser.parseDisks(null)).isEmpty();
    }
  }

  @Nested
  class SmartDetail {

    @Test
    void full_attribute_table_is_carried_through() {
      JsonNode disk = json(REALLOCATING_DISK);
      DiskSmart smart = DisksStateParser.parseSmart(disk, "ata-WDC_WD80EFZX-68UW8N0_VKHA1B2C", "2026-08-14T02:15:00Z");
      assertThat(smart.diskId()).isEqualTo("ata-WDC_WD80EFZX-68UW8N0_VKHA1B2C");
      assertThat(smart.supported()).isTrue();
      assertThat(smart.overall()).isEqualTo("warning");
      assertThat(smart.collectedAt()).isEqualTo("2026-08-14T02:15:00Z");
      assertThat(smart.attributes()).hasSize(2);
      assertThat(smart.attributes().get(0).id()).isEqualTo(5);
      assertThat(smart.attributes().get(0).raw()).isEqualTo("3");
    }

    @Test
    void unsupported_disk_has_no_attributes() {
      DiskSmart smart = DisksStateParser.parseSmart(json(UNSUPPORTED_DISK), "usb-1", "2026-08-14T02:15:00Z");
      assertThat(smart.supported()).isFalse();
      assertThat(smart.overall()).isEqualTo("unknown");
      assertThat(smart.attributes()).isEmpty();
    }
  }

  @Nested
  class PoolDerivation {

    @Test
    void configured_pool_derives_branches_and_totals_from_data_disks() {
      String root = "{\"disks\": [" + HEALTHY_DISK + "," + REALLOCATING_DISK + "], "
          + "\"pool\": {\"configured\": true, \"mountpoint\": \"/mnt/storage\", "
          + "\"createPolicy\": \"mfs\", \"minFreeBytes\": 21474836480}}";
      JsonNode rootNode = json(root);
      List<Disk> disks = DisksStateParser.parseDisks(rootNode);
      Pool pool = DisksStateParser.parsePool(rootNode, disks);

      assertThat(pool.configured()).isTrue();
      assertThat(pool.mountpoint()).isEqualTo("/mnt/storage");
      assertThat(pool.createPolicy()).isEqualTo("mfs");
      assertThat(pool.minFreeBytes()).isEqualTo(21474836480L);
      // Only the "data" role disk (sdb) counts; sda is "system".
      assertThat(pool.branches()).hasSize(1);
      assertThat(pool.branches().get(0).diskId()).isEqualTo("ata-WDC_WD80EFZX-68UW8N0_VKHA1B2C");
      assertThat(pool.totalBytes()).isEqualTo(8001563222016L);
      assertThat(pool.usedBytes()).isEqualTo(5263670148096L);
    }

    @Test
    void not_configured_pool_has_no_branches_even_if_data_disks_exist() {
      String root = "{\"disks\": [" + REALLOCATING_DISK + "], "
          + "\"pool\": {\"configured\": false}}";
      JsonNode rootNode = json(root);
      Pool pool = DisksStateParser.parsePool(rootNode, DisksStateParser.parseDisks(rootNode));

      assertThat(pool.configured()).isFalse();
      assertThat(pool.branches()).isEmpty();
      assertThat(pool.totalBytes()).isNull();
      assertThat(pool.mountpoint()).isNull();
    }

    @Test
    void missing_disks_json_is_an_unconfigured_pool() {
      Pool pool = DisksStateParser.parsePool(null, List.of());
      assertThat(pool.configured()).isFalse();
      assertThat(pool.branches()).isEmpty();
    }
  }

  @Nested
  class ParityDerivation {

    @Test
    void configured_and_parity_disk_ids_come_from_disk_roles_not_the_parity_file() {
      List<Disk> disks = List.of(DisksStateParser.parseDisk(json(FAILING_DISK))); // role: parity
      Parity parity = DisksStateParser.parseParity(null, disks, 3);

      assertThat(parity.configured()).isTrue();
      assertThat(parity.parityDiskIds()).containsExactly("ata-TOSHIBA_MG08ACA16TE_91K0A004FVGG");
      // No parity.json yet: honest "never", not "not configured".
      assertThat(parity.lastSyncState()).isEqualTo("never");
      assertThat(parity.lastSyncAt()).isNull();
      assertThat(parity.stalenessWarnDays()).isEqualTo(3);
    }

    @Test
    void no_parity_role_disk_means_not_configured_regardless_of_the_parity_file() {
      String parityJson = """
          {"lastSyncAt": "2026-08-10T03:31:00Z", "lastSyncState": "ok"}
          """;
      Parity parity = DisksStateParser.parseParity(json(parityJson), List.of(), 3);
      assertThat(parity.configured()).isFalse();
      assertThat(parity.parityDiskIds()).isEmpty();
    }

    @Test
    void reads_timing_and_counts_from_the_parity_file() {
      List<Disk> disks = List.of(DisksStateParser.parseDisk(json(FAILING_DISK)));
      String parityJson = """
          {
            "collectedAt": "2026-08-14T03:31:42Z",
            "lastSyncAt": "2026-08-10T03:31:00Z",
            "lastSyncState": "ok",
            "lastScrubAt": "2026-08-10T03:45:12Z",
            "pendingChanges": 1842,
            "deletedSinceSync": 17,
            "deletionThreshold": 200,
            "stalenessWarnDays": 3
          }
          """;
      Parity parity = DisksStateParser.parseParity(json(parityJson), disks, 3);

      assertThat(parity.lastSyncAt()).isEqualTo("2026-08-10T03:31:00Z");
      assertThat(parity.lastSyncState()).isEqualTo("ok");
      assertThat(parity.lastScrubAt()).isEqualTo("2026-08-10T03:45:12Z");
      assertThat(parity.pendingChanges()).isEqualTo(1842);
      assertThat(parity.deletedSinceSync()).isEqualTo(17);
      assertThat(parity.deletionThreshold()).isEqualTo(200);
    }

    /**
     * {@code aborted} is a deliberate guard, not a fault — see
     * snapraid-runner's deletion threshold. The parser must not collapse
     * it into "failed".
     */
    @Test
    void aborted_state_is_distinct_from_failed() {
      List<Disk> disks = List.of(DisksStateParser.parseDisk(json(FAILING_DISK)));
      String parityJson = """
          {"lastSyncAt": null, "lastSyncState": "aborted", "deletedSinceSync": 250, "deletionThreshold": 200}
          """;
      Parity parity = DisksStateParser.parseParity(json(parityJson), disks, 3);
      assertThat(parity.lastSyncState()).isEqualTo("aborted");
      assertThat(parity.deletedSinceSync()).isEqualTo(250);
    }
  }
}
