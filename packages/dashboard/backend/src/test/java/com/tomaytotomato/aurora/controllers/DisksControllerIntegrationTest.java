package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.support.AuroraIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/disks} against real files on disk and the real HTTP + Spring
 * Security layers. Only {@code CommandRunner} is faked, for the two
 * SnapRAID actions — every GET here is a file read.
 *
 * <p>Covers the page states {@code DISKS_PAGE_DESIGN.md} describes
 * (no-pool, healthy, a warning drive, a failing drive, stale/aborted/never
 * parity) plus the two file-absence cases: no collection has ever run, and
 * a collection that ran but is old. Both are meant to degrade to honest
 * defaults rather than 500.
 */
@WithMockUser
class DisksControllerIntegrationTest extends AuroraIntegrationTest {

  private static final String DISKS_STATE_PATH = "packages/dashboard/state/disks.json";
  private static final String PARITY_STATE_PATH = "packages/dashboard/state/parity.json";

  private void writeDisksState(String json) throws IOException {
    writeRepoFile(DISKS_STATE_PATH, json);
  }

  private void writeParityState(String json) throws IOException {
    writeRepoFile(PARITY_STATE_PATH, json);
  }

  private void awaitInvocation(String argvFragment) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
    while (!commands.ran(argvFragment) && Instant.now().isBefore(deadline)) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    assertThat(commands.ran(argvFragment))
        .as("expected a command containing '%s' to have run", argvFragment)
        .isTrue();
  }

  // ------------------------------------------------------------------

  @Nested
  @DisplayName("no collection has ever run")
  class NoStateFile {

    @Test
    void list_is_empty_rather_than_an_error() throws Exception {
      mvc.perform(get("/api/disks"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$").isArray())
          .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void pool_is_honestly_unconfigured() throws Exception {
      mvc.perform(get("/api/disks/pool"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.configured").value(false))
          .andExpect(jsonPath("$.mountpoint").doesNotExist())
          .andExpect(jsonPath("$.branches.length()").value(0));
    }

    @Test
    void parity_reports_never_synced() throws Exception {
      mvc.perform(get("/api/disks/parity"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.configured").value(false))
          .andExpect(jsonPath("$.parityDiskIds.length()").value(0))
          .andExpect(jsonPath("$.lastSyncState").value("never"))
          .andExpect(jsonPath("$.lastSyncAt").doesNotExist());
    }

    @Test
    void any_disk_id_is_404_on_the_smart_endpoint() throws Exception {
      mvc.perform(get("/api/disks/{id}/smart", "no-such-disk"))
          .andExpect(status().isNotFound());
    }
  }

  @Nested
  @DisplayName("one disk, no pool")
  class NoPoolSingleDisk {

    @Test
    void still_shows_smart_for_the_single_disk() throws Exception {
      writeDisksState("""
          {
            "collectedAt": "2026-08-14T02:00:00Z",
            "disks": [{
              "id": "ata-single-disk", "device": "/dev/sda", "model": "Solo Drive",
              "serial": "S1", "sizeBytes": 500107862016, "role": "system",
              "mountpoint": "/", "filesystem": "ext4", "usedBytes": 229920788480,
              "smart": {"supported": true, "smart_status": {"passed": true},
                "power_on_time": {"hours": 100}, "temperature": {"current": 30},
                "ata_smart_attributes": {"table": []}}
            }],
            "pool": {"configured": false}
          }
          """);

      mvc.perform(get("/api/disks"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(1))
          .andExpect(jsonPath("$[0].id").value("ata-single-disk"))
          .andExpect(jsonPath("$[0].health").value("passed"));

      mvc.perform(get("/api/disks/pool"))
          .andExpect(jsonPath("$.configured").value(false))
          .andExpect(jsonPath("$.branches.length()").value(0));
    }
  }

  @Nested
  @DisplayName("a pool of healthy disks")
  class PoolHealthy {

    @Test
    void pool_totals_and_branches_come_from_the_data_disks() throws Exception {
      writeDisksState("""
          {
            "collectedAt": "2026-08-14T02:00:00Z",
            "disks": [
              {"id": "d1", "device": "/dev/sdb", "sizeBytes": 8000000000000, "role": "data",
               "mountpoint": "/mnt/disk1", "filesystem": "ext4", "usedBytes": 4000000000000,
               "smart": {"supported": true, "smart_status": {"passed": true}, "ata_smart_attributes": {"table": []}}},
              {"id": "d2", "device": "/dev/sdc", "sizeBytes": 8000000000000, "role": "data",
               "mountpoint": "/mnt/disk2", "filesystem": "ext4", "usedBytes": 2000000000000,
               "smart": {"supported": true, "smart_status": {"passed": true}, "ata_smart_attributes": {"table": []}}}
            ],
            "pool": {"configured": true, "mountpoint": "/mnt/storage", "createPolicy": "mfs", "minFreeBytes": 21474836480}
          }
          """);

      mvc.perform(get("/api/disks/pool"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.configured").value(true))
          .andExpect(jsonPath("$.mountpoint").value("/mnt/storage"))
          .andExpect(jsonPath("$.branches.length()").value(2))
          .andExpect(jsonPath("$.totalBytes").value(16000000000000L))
          .andExpect(jsonPath("$.usedBytes").value(6000000000000L));
    }
  }

  @Nested
  @DisplayName("a drive with reallocated sectors")
  class DriveWarning {

    @Test
    void reads_as_warning_even_though_smart_status_passed() throws Exception {
      writeDisksState("""
          {
            "collectedAt": "2026-08-14T02:00:00Z",
            "disks": [{
              "id": "d-warn", "device": "/dev/sdb", "sizeBytes": 8000000000000, "role": "data",
              "mountpoint": "/mnt/disk1", "filesystem": "ext4", "usedBytes": 4000000000000,
              "smart": {
                "supported": true, "smart_status": {"passed": true},
                "ata_smart_attributes": {"table": [
                  {"id": 5, "name": "Reallocated_Sector_Ct", "value": 198, "worst": 198, "thresh": 140, "raw": {"value": 3, "string": "3"}}
                ]},
                "ata_smart_self_test_log": {"standard": {"table": [
                  {"status": {"string": "Completed without error", "passed": true}}
                ]}}
              }
            }],
            "pool": {"configured": false}
          }
          """);

      mvc.perform(get("/api/disks"))
          .andExpect(jsonPath("$[0].health").value("warning"))
          .andExpect(jsonPath("$[0].reallocatedSectors").value(3));
    }
  }

  @Nested
  @DisplayName("a drive smartctl calls failing")
  class DriveFailing {

    @Test
    void reads_as_failing() throws Exception {
      writeDisksState("""
          {
            "collectedAt": "2026-08-14T02:00:00Z",
            "disks": [{
              "id": "d-fail", "device": "/dev/sdd", "sizeBytes": 16000000000000, "role": "parity",
              "mountpoint": "/mnt/parity1", "filesystem": "ext4", "usedBytes": 8000000000000,
              "smart": {"supported": true, "smart_status": {"passed": false}, "ata_smart_attributes": {"table": []}}
            }],
            "pool": {"configured": false}
          }
          """);

      mvc.perform(get("/api/disks"))
          .andExpect(jsonPath("$[0].health").value("failing"));
    }
  }

  @Nested
  @DisplayName("parity states")
  class ParityStates {

    @Test
    void configured_and_ids_come_from_disk_roles() throws Exception {
      writeDisksState("""
          {
            "collectedAt": "2026-08-14T02:00:00Z",
            "disks": [{
              "id": "parity-1", "device": "/dev/sdd", "sizeBytes": 16000000000000, "role": "parity",
              "mountpoint": "/mnt/parity1", "filesystem": "ext4", "usedBytes": 8000000000000,
              "smart": {"supported": true, "smart_status": {"passed": true}, "ata_smart_attributes": {"table": []}}
            }],
            "pool": {"configured": false}
          }
          """);
      writeParityState("""
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
          """);

      mvc.perform(get("/api/disks/parity"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.configured").value(true))
          .andExpect(jsonPath("$.parityDiskIds[0]").value("parity-1"))
          .andExpect(jsonPath("$.lastSyncState").value("ok"))
          .andExpect(jsonPath("$.lastSyncAt").value("2026-08-10T03:31:00Z"))
          .andExpect(jsonPath("$.pendingChanges").value(1842))
          .andExpect(jsonPath("$.deletedSinceSync").value(17));
    }

    @Test
    void aborted_is_distinct_from_failed() throws Exception {
      writeDisksState("""
          {"disks": [{"id": "p1", "device": "/dev/sdd", "sizeBytes": 1, "role": "parity", "smart": {"supported": false}}]}
          """);
      writeParityState("""
          {"lastSyncState": "aborted", "deletedSinceSync": 250, "deletionThreshold": 200}
          """);

      mvc.perform(get("/api/disks/parity"))
          .andExpect(jsonPath("$.lastSyncState").value("aborted"))
          .andExpect(jsonPath("$.deletedSinceSync").value(250));
    }

    @Test
    void a_stale_collection_is_surfaced_honestly_not_hidden() throws Exception {
      // No freshness gate on the backend: an old collectedAt / lastSyncAt
      // is passed straight through so the frontend's own staleness rule
      // can act on it.
      writeDisksState("""
          {"disks": [{"id": "p1", "device": "/dev/sdd", "sizeBytes": 1, "role": "parity", "smart": {"supported": false}}]}
          """);
      writeParityState("""
          {"lastSyncAt": "2020-01-01T00:00:00Z", "lastSyncState": "ok"}
          """);

      mvc.perform(get("/api/disks/parity"))
          .andExpect(jsonPath("$.lastSyncAt").value("2020-01-01T00:00:00Z"))
          .andExpect(jsonPath("$.lastSyncState").value("ok"));
    }
  }

  @Nested
  @DisplayName("GET /disks/{id}/smart")
  class SmartDetail {

    @Test
    void unknown_id_is_404() throws Exception {
      writeDisksState("""
          {"disks": [{"id": "known", "device": "/dev/sda", "sizeBytes": 1, "role": "system", "smart": {"supported": false}}]}
          """);

      mvc.perform(get("/api/disks/{id}/smart", "unknown"))
          .andExpect(status().isNotFound());
    }

    @Test
    void a_known_disk_with_no_smart_support_is_200_not_404() throws Exception {
      writeDisksState("""
          {"disks": [{"id": "usb-1", "device": "/dev/sde", "sizeBytes": 1, "role": "unassigned", "smart": {"supported": false}}]}
          """);

      mvc.perform(get("/api/disks/{id}/smart", "usb-1"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.supported").value(false))
          .andExpect(jsonPath("$.overall").value("unknown"))
          .andExpect(jsonPath("$.attributes.length()").value(0));
    }

    @Test
    void the_full_attribute_table_and_freshness_are_returned() throws Exception {
      writeDisksState("""
          {
            "collectedAt": "2026-08-14T02:15:03Z",
            "disks": [{
              "id": "d1", "device": "/dev/sdb", "sizeBytes": 8000000000000, "role": "data",
              "smart": {
                "supported": true, "smart_status": {"passed": true},
                "ata_smart_attributes": {"table": [
                  {"id": 5, "name": "Reallocated_Sector_Ct", "value": 200, "worst": 200, "thresh": 140, "raw": {"value": 0, "string": "0"}}
                ]}
              }
            }]
          }
          """);

      mvc.perform(get("/api/disks/{id}/smart", "d1"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.diskId").value("d1"))
          .andExpect(jsonPath("$.supported").value(true))
          .andExpect(jsonPath("$.overall").value("passed"))
          .andExpect(jsonPath("$.collectedAt").value("2026-08-14T02:15:03Z"))
          .andExpect(jsonPath("$.attributes.length()").value(1))
          .andExpect(jsonPath("$.attributes[0].name").value("Reallocated_Sector_Ct"));
    }
  }

  @Nested
  @DisplayName("the two SnapRAID actions")
  class ParityActions {

    @Test
    void sync_runs_the_allow_listed_helper_with_a_fixed_argv() throws Exception {
      mvc.perform(post("/api/disks/parity/sync"))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.jobId").isNotEmpty());

      awaitInvocation("aurora-parity-action");
      var invocation = commands.firstMatching("aurora-parity-action");
      assertThat(invocation.argv()).containsExactly("/usr/local/bin/aurora-parity-action", "sync");
    }

    @Test
    void scrub_runs_the_allow_listed_helper_with_a_fixed_argv() throws Exception {
      mvc.perform(post("/api/disks/parity/scrub"))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.jobId").isNotEmpty());

      awaitInvocation("aurora-parity-action");
      var invocation = commands.firstMatching("aurora-parity-action");
      assertThat(invocation.argv()).containsExactly("/usr/local/bin/aurora-parity-action", "scrub");
    }

    @Test
    void a_missing_helper_binary_still_reaches_a_terminal_job_state() throws Exception {
      commands.stubMissingBinary("aurora-parity-action");

      String body = mvc.perform(post("/api/disks/parity/sync"))
          .andExpect(status().isAccepted())
          .andReturn().getResponse().getContentAsString();
      String jobId = body.replaceAll(".*\"jobId\"\\s*:\\s*\"([^\"]+)\".*", "$1");

      Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
      String lastBody = "";
      while (Instant.now().isBefore(deadline)) {
        lastBody = mvc.perform(get("/api/jobs/{id}", jobId)).andReturn()
            .getResponse().getContentAsString();
        if (lastBody.contains("\"state\":\"failed\"")) break;
        Thread.sleep(10);
      }
      assertThat(lastBody).contains("\"state\":\"failed\"");
    }
  }
}
