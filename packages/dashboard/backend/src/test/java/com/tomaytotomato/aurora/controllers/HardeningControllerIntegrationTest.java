package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.support.AuroraIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/security/hardening} against a real repository tree.
 *
 * <p>Each test writes the files it cares about into the seeded fake repo,
 * which is the whole reason the harness gives tests a real directory: the
 * behaviour under test is "what does the repository actually say".
 */
@WithMockUser
class HardeningControllerIntegrationTest extends AuroraIntegrationTest {

  private static String composeWith(String... lines) {
    return "name: aurora-thing\nservices:\n  thing:\n" + String.join("\n", lines) + "\n";
  }

  @Nested
  @DisplayName("image pinning")
  class Pinning {

    @Test
    void counts_a_digest_pinned_image_as_pinned() throws Exception {
      writeRepoFile("packages/alpha/compose.yml",
          composeWith("    image: caddy:2.8@sha256:aaaa"));

      mvc.perform(get("/api/security/hardening"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.pinning.total").value(1))
          .andExpect(jsonPath("$.pinning.pinned").value(1))
          .andExpect(jsonPath("$.pinning.unpinned.length()").value(0));
    }

    @Test
    void counts_a_floating_tag_and_names_it() throws Exception {
      writeRepoFile("packages/alpha/compose.yml",
          composeWith("    image: lscr.io/linuxserver/sonarr:latest"));

      mvc.perform(get("/api/security/hardening"))
          .andExpect(jsonPath("$.pinning.pinned").value(0))
          .andExpect(jsonPath("$.pinning.unpinned[0]").value("lscr.io/linuxserver/sonarr:latest"));
    }

    @Test
    void reads_the_repository_rather_than_what_happens_to_be_running() throws Exception {
      // A package nobody has enabled still has an unpinned image waiting
      // for the next rebuild, which is the question this page answers.
      writeRepoFile("packages/never-enabled/compose.yml", composeWith("    image: nginx"));

      mvc.perform(get("/api/security/hardening"))
          .andExpect(jsonPath("$.pinning.total").value(1))
          .andExpect(jsonPath("$.pinning.unpinned[0]").value("nginx"));
    }

    @Test
    void lists_each_offending_image_once_however_many_files_use_it() throws Exception {
      writeRepoFile("packages/alpha/compose.yml", composeWith("    image: nginx:latest"));
      writeRepoFile("packages/beta/compose.yml", composeWith("    image: nginx:latest"));

      mvc.perform(get("/api/security/hardening"))
          .andExpect(jsonPath("$.pinning.total").value(2))
          .andExpect(jsonPath("$.pinning.unpinned.length()").value(1));
    }

    @Test
    void reports_a_missing_pins_file_honestly() throws Exception {
      deleteRepoFile("pins.env");

      mvc.perform(get("/api/security/hardening"))
          .andExpect(jsonPath("$.pinning.pinsFileExists").value(false))
          .andExpect(jsonPath("$.pinning.generatedAt").doesNotExist());
    }

    @Test
    void reports_the_pins_file_when_it_is_there() throws Exception {
      writeRepoFile("pins.env", "CADDY_DIGEST=sha256:aaaa\n");

      mvc.perform(get("/api/security/hardening"))
          .andExpect(jsonPath("$.pinning.pinsFileExists").value(true))
          .andExpect(jsonPath("$.pinning.generatedAt").isNotEmpty());
    }
  }

  @Nested
  @DisplayName("secrets at rest")
  class Secrets {

    @Test
    void counts_plain_env_files_as_unencrypted() throws Exception {
      writeRepoFile("packages/alpha/.env", "DB_PASSWORD=hunter2\n");
      writeRepoFile("packages/beta/.env", "API_KEY=abcdef\n");

      mvc.perform(get("/api/security/hardening"))
          .andExpect(jsonPath("$.secrets.envFiles").value(2))
          .andExpect(jsonPath("$.secrets.encryptedFiles").value(0))
          .andExpect(jsonPath("$.secrets.encrypted").value(false))
          .andExpect(jsonPath("$.secrets.method").doesNotExist());
    }

    @Test
    void recognises_a_sops_encrypted_file() throws Exception {
      writeRepoFile("packages/alpha/.env",
          "DB_PASSWORD=ENC[AES256_GCM,data:abcd,iv:efgh,tag:ijkl,type:str]\nsops:\n  age: []\n");

      mvc.perform(get("/api/security/hardening"))
          .andExpect(jsonPath("$.secrets.encryptedFiles").value(1))
          .andExpect(jsonPath("$.secrets.encrypted").value(true))
          .andExpect(jsonPath("$.secrets.method").value("sops-age"));
    }

    @Test
    void half_encrypted_is_not_encrypted() throws Exception {
      writeRepoFile("packages/alpha/.env", "sops:\n  age: []\n");
      writeRepoFile("packages/beta/.env", "API_KEY=still-in-the-clear\n");

      mvc.perform(get("/api/security/hardening"))
          .andExpect(jsonPath("$.secrets.envFiles").value(2))
          .andExpect(jsonPath("$.secrets.encryptedFiles").value(1))
          .andExpect(jsonPath("$.secrets.encrypted").value(false));
    }

    @Test
    void a_box_with_nothing_to_encrypt_does_not_get_a_green_tick() throws Exception {
      // Vacuous truth would be the easy answer and the wrong one: an empty
      // box is not a hardened box.
      mvc.perform(get("/api/security/hardening"))
          .andExpect(jsonPath("$.secrets.envFiles").value(0))
          .andExpect(jsonPath("$.secrets.encrypted").value(false));
    }
  }

  @Nested
  @DisplayName("the docker socket")
  class Socket {

    @Test
    void names_the_package_that_mounts_it() throws Exception {
      writeRepoFile("packages/dashboard/compose.yml", composeWith(
          "    volumes:",
          "      - /var/run/docker.sock:/var/run/docker.sock:rw"));

      mvc.perform(get("/api/security/hardening"))
          .andExpect(jsonPath("$.dockerSocket.exposedContainers[0]").value("dashboard"))
          .andExpect(jsonPath("$.dockerSocket.writable").value(true))
          .andExpect(jsonPath("$.dockerSocket.proxied").value(false));
    }

    @Test
    void treats_an_unspecified_mode_as_writable() throws Exception {
      // Docker's default for a bind mount is read-write, so an omitted
      // suffix is the dangerous case and does not get the benefit of the
      // doubt.
      writeRepoFile("packages/git/compose.yml", composeWith(
          "    volumes:",
          "      - /var/run/docker.sock:/var/run/docker.sock"));

      mvc.perform(get("/api/security/hardening"))
          .andExpect(jsonPath("$.dockerSocket.writable").value(true));
    }

    @Test
    void a_read_only_mount_is_still_exposure_but_not_writable() throws Exception {
      writeRepoFile("packages/git/compose.yml", composeWith(
          "    volumes:",
          "      - /var/run/docker.sock:/var/run/docker.sock:ro"));

      mvc.perform(get("/api/security/hardening"))
          .andExpect(jsonPath("$.dockerSocket.exposedContainers.length()").value(1))
          .andExpect(jsonPath("$.dockerSocket.writable").value(false));
    }

    @Test
    void is_only_proxied_when_nothing_mounts_the_socket_directly() throws Exception {
      // A proxy that exists alongside a container still holding the raw
      // socket has not solved anything.
      writeRepoFile("packages/core/compose.yml", composeWith(
          "    image: tecnativa/docker-socket-proxy:0.2"));
      writeRepoFile("packages/dashboard/compose.yml", composeWith(
          "    volumes:",
          "      - /var/run/docker.sock:/var/run/docker.sock:rw"));

      mvc.perform(get("/api/security/hardening"))
          .andExpect(jsonPath("$.dockerSocket.proxied").value(false));
    }

    @Test
    void reports_done_when_a_proxy_is_present_and_nothing_holds_the_socket() throws Exception {
      writeRepoFile("packages/core/compose.yml", composeWith(
          "    image: tecnativa/docker-socket-proxy:0.2"));

      mvc.perform(get("/api/security/hardening"))
          .andExpect(jsonPath("$.dockerSocket.proxied").value(true))
          .andExpect(jsonPath("$.dockerSocket.exposedContainers.length()").value(0));
    }
  }

  @Nested
  @DisplayName("a repository it cannot read")
  class Degraded {

    @Test
    void an_empty_repo_reports_zeroes_rather_than_failing() throws Exception {
      deleteRepoFile("packages/core/manifest.yml");
      deleteRepoFile("packages/notes/manifest.yml");

      mvc.perform(get("/api/security/hardening"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.pinning.total").value(0))
          .andExpect(jsonPath("$.secrets.envFiles").value(0))
          .andExpect(jsonPath("$.dockerSocket.exposedContainers.length()").value(0));
    }
  }
}
