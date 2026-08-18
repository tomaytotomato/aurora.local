package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.support.AuroraIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;

import java.io.IOException;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/backup} — the read path.
 *
 * <p>Aurora has shipped Kopia since early on and never said a word about
 * it in the dashboard, so to find out whether your data is safe you had to
 * log into a second web UI on port 51515. In practice nobody looks, and a
 * backup nobody looks at is one that quietly stopped working in March.
 * See {@code docs/BACKUP_PAGE_DESIGN.md}.
 *
 * <p>The honesty rules from §4 of that spec are what most of these tests
 * pin, because the failure mode here is not a 500 — it is a page that
 * cheerfully reports "protected" about data nobody has ever copied.
 */
@WithMockUser
class BackupControllerIntegrationTest extends AuroraIntegrationTest {

  /** `kopia repository status --json` on a connected filesystem repo. */
  private static final String REPO_STATUS_JSON = """
      {"connected":true,"storage":"filesystem","configFile":"/app/config/repository.config",
       "storageConfig":{"path":"/repository"},"encryption":{"algorithm":"AES256-GCM-HMAC-SHA256"},
       "contentCount":1420,"totalSize":8123456789,"uniqueSize":2048576000}
      """;

  /** `kopia snapshot list --json`: two sources, one healthy, one failed. */
  private static final String SNAPSHOT_LIST_JSON = """
      [{"id":"snap-1","source":{"path":"/protected/data/photos/library"},
        "startTime":"2026-08-18T02:00:00Z","endTime":"2026-08-18T02:04:00Z",
        "stats":{"totalSize":7000000000,"fileCount":42000},"incomplete":""},
       {"id":"snap-2","source":{"path":"/protected/data/notes"},
        "startTime":"2026-08-18T02:04:00Z","endTime":"2026-08-18T02:04:30Z",
        "stats":{"totalSize":1200000,"fileCount":310},"incomplete":"partial"}]
      """;

  /**
   * The backup package has to be enabled for any of this to mean
   * anything, and the fake repo does not enable it by default.
   */
  @BeforeEach
  void enableBackupPackage() throws IOException {
    writeRepoFile("packages/backup/manifest.yml", """
        name: backup
        title: Backup (Kopia)
        description: fixture
        category: backup
        depends_on: [core]
        source_url: https://github.com/kopia/kopia
        """);
    writeRepoFile(".state.yml", """
        bootstrap_version: 1
        enabled:
          - core
          - backup
          - photos
        profiles: []
        """);
  }

  @Nested
  @DisplayName("status")
  class Status {

    @Test
    @DisplayName("a repository Kopia cannot reach is unreachable, not empty")
    void unreachable_repository_says_so() throws Exception {
      // §4: never show a size for a repository Aurora could not reach.
      // Reporting zeroes would read as "nothing to back up", which is the
      // opposite of the truth.
      commands.stubFailure("kopia repository status", 1, "ERROR unable to open repository");

      mvc.perform(get("/api/backup/status"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.repoState").value("unreachable"))
          .andExpect(jsonPath("$.totalSizeBytes").value(org.hamcrest.Matchers.nullValue()))
          .andExpect(jsonPath("$.uniqueSizeBytes").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("a repository that was never initialised is not-configured")
    void never_initialised_is_not_configured() throws Exception {
      commands.stubFailure("kopia repository status", 1,
          "ERROR: repository is not connected. See https://kopia.io/docs/repositories/");

      mvc.perform(get("/api/backup/status"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.repoState").value("not-configured"));
    }

    @Test
    @DisplayName("a connected repository reports both sizes, because dedup is the surprising number")
    void connected_repository_reports_the_facts() throws Exception {
      commands.stubLines("kopia repository status", REPO_STATUS_JSON);
      commands.stubLines("kopia snapshot list", SNAPSHOT_LIST_JSON);

      mvc.perform(get("/api/backup/status"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.repoState").value("connected"))
          .andExpect(jsonPath("$.repoKind").value("filesystem"))
          .andExpect(jsonPath("$.encrypted").value(true))
          .andExpect(jsonPath("$.totalSizeBytes").value(8123456789L))
          .andExpect(jsonPath("$.uniqueSizeBytes").value(2048576000L))
          .andExpect(jsonPath("$.snapshotCount").value(2));
    }

    @Test
    @DisplayName("the last run is the most recent snapshot, and a partial one is not 'ok'")
    void last_run_reflects_the_most_recent_snapshot() throws Exception {
      commands.stubLines("kopia repository status", REPO_STATUS_JSON);
      commands.stubLines("kopia snapshot list", SNAPSHOT_LIST_JSON);

      mvc.perform(get("/api/backup/status"))
          .andExpect(status().isOk())
          // When the run FINISHED, not when it started: "last backed up
          // at" is a statement about when the data was safe, and snap-2
          // ran 02:04:00 → 02:04:30.
          .andExpect(jsonPath("$.lastRunAt").value("2026-08-18T02:04:30Z"))
          // snap-2 is the later one and carries incomplete: "partial".
          .andExpect(jsonPath("$.lastRunState").value("partial"))
          .andExpect(jsonPath("$.lastRunDurationMs").value(30000));
    }

    @Test
    @DisplayName("encrypted reflects what Kopia reports, not what the README hopes")
    void encryption_comes_from_kopia() throws Exception {
      commands.stubLines("kopia repository status",
          "{\"connected\":true,\"storage\":\"filesystem\",\"encryption\":{\"algorithm\":\"NONE\"}}");
      commands.stubLines("kopia snapshot list", "[]");

      mvc.perform(get("/api/backup/status"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.encrypted").value(false));
    }
  }

  @Nested
  @DisplayName("what's protected")
  class Sources {

    @Test
    @DisplayName("a path a package declares but Kopia has never snapshotted still appears")
    void declared_but_never_snapshotted_is_listed() throws Exception {
      // The whole point. A manifest saying "these are my paths" plus a
      // repository that has never heard of them is exactly the box that
      // believes it is backed up and is not. Omitting the row, or only
      // listing what Kopia knows, would hide it.
      commands.stubLines("kopia repository status", REPO_STATUS_JSON);
      commands.stubLines("kopia snapshot list", "[]");

      mvc.perform(get("/api/backup/sources"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[?(@.path=='data/photos/library')].package")
              .value(containsInAnyOrder("photos")))
          .andExpect(jsonPath("$[?(@.path=='data/photos/library')].lastSnapshotAt")
              .value(containsInAnyOrder(org.hamcrest.Matchers.nullValue())))
          .andExpect(jsonPath("$[?(@.path=='data/photos/library')].lastSnapshotState")
              .value(containsInAnyOrder(org.hamcrest.Matchers.nullValue())));
    }

    @Test
    @DisplayName("a snapshotted path carries its real size, file count and state")
    void snapshotted_path_carries_kopia_facts() throws Exception {
      commands.stubLines("kopia repository status", REPO_STATUS_JSON);
      commands.stubLines("kopia snapshot list", SNAPSHOT_LIST_JSON);

      mvc.perform(get("/api/backup/sources"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[?(@.path=='data/photos/library')].sizeBytes")
              .value(containsInAnyOrder(7000000000L)))
          .andExpect(jsonPath("$[?(@.path=='data/photos/library')].fileCount")
              .value(containsInAnyOrder(42000)))
          .andExpect(jsonPath("$[?(@.path=='data/photos/library')].lastSnapshotState")
              .value(containsInAnyOrder("ok")));
    }

    @Test
    @DisplayName("the manifest's before-actions come through, so the page can say what runs first")
    void before_actions_are_served() throws Exception {
      commands.stubLines("kopia repository status", REPO_STATUS_JSON);
      commands.stubLines("kopia snapshot list", "[]");

      mvc.perform(get("/api/backup/sources"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[?(@.path=='data/photos/library')].beforeActions[0].kind")
              .value(containsInAnyOrder("postgres-dump")))
          .andExpect(jsonPath("$[?(@.path=='data/photos/library')].beforeActions[0].container")
              .value(containsInAnyOrder("immich-postgres")));
    }

    @Test
    @DisplayName("a database with a declared dump is not flagged")
    void declared_dump_clears_the_warning() throws Exception {
      // fake-repo photos declares postgres-dump against immich-postgres,
      // which is exactly the mitigation, so no warning.
      commands.stubLines("kopia repository status", REPO_STATUS_JSON);
      commands.stubLines("kopia snapshot list", "[]");

      mvc.perform(get("/api/backup/sources"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[?(@.path=='data/photos/library')].needsConsistencyAction")
              .value(containsInAnyOrder(false)));
    }

    @Test
    @DisplayName("a database with NO declared dump is flagged, not quietly listed as fine")
    void undeclared_database_is_flagged() throws Exception {
      // A Postgres data directory copied while the server is running is
      // not a backup, it is a corrupted file with a timestamp. §4 says
      // call it out rather than leave it to be discovered during a
      // restore.
      writeRepoFile("packages/documents/manifest.yml", """
          name: documents
          title: Documents
          description: fixture
          category: productivity
          depends_on: [core]
          source_url: https://github.com/paperless-ngx/paperless-ngx
          backup:
            paths:
              - data/documents/media
            before: []
          """);
      writeRepoFile("packages/documents/compose.yml", """
          name: aurora-documents
          services:
            paperless:
              image: ghcr.io/paperless-ngx/paperless-ngx:2.11
            db:
              image: docker.io/library/postgres:16
          """);
      writeRepoFile(".state.yml", """
          bootstrap_version: 1
          enabled:
            - core
            - backup
            - documents
          profiles: []
          """);
      commands.stubLines("kopia repository status", REPO_STATUS_JSON);
      commands.stubLines("kopia snapshot list", "[]");

      mvc.perform(get("/api/backup/sources"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[?(@.path=='data/documents/media')].needsConsistencyAction")
              .value(containsInAnyOrder(true)));
    }

    @Test
    @DisplayName("a package with no database at all is not flagged")
    void a_package_without_a_database_is_not_flagged() throws Exception {
      writeRepoFile("packages/notes/compose.yml", """
          name: aurora-notes
          services:
            silverbullet:
              image: ghcr.io/silverbulletmd/silverbullet:2
          """);
      writeRepoFile("packages/notes/manifest.yml", """
          name: notes
          title: Notes
          description: fixture
          category: productivity
          depends_on: [core]
          source_url: https://github.com/silverbulletmd/silverbullet
          backup:
            paths:
              - data/notes
            before: []
          """);
      writeRepoFile(".state.yml", """
          bootstrap_version: 1
          enabled:
            - core
            - backup
            - notes
          profiles: []
          """);
      commands.stubLines("kopia repository status", REPO_STATUS_JSON);
      commands.stubLines("kopia snapshot list", "[]");

      mvc.perform(get("/api/backup/sources"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[?(@.path=='data/notes')].needsConsistencyAction")
              .value(containsInAnyOrder(false)));
    }

    @Test
    @DisplayName("an unreachable repository does not turn every source into a zero")
    void unreachable_repository_leaves_sources_honest() throws Exception {
      commands.stubFailure("kopia repository status", 1, "ERROR unable to open repository");

      mvc.perform(get("/api/backup/sources"))
          .andExpect(status().isOk())
          // Still lists what the manifests claim, with no invented facts
          // about it.
          .andExpect(jsonPath("$[?(@.path=='data/photos/library')].sizeBytes")
              .value(containsInAnyOrder(org.hamcrest.Matchers.nullValue())));
    }
  }
}
