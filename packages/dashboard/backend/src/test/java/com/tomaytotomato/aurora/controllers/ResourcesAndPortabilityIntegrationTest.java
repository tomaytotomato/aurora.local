package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.support.AuroraIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

import java.io.IOException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/packages/{name}/resources} and the settings export/import
 * pair. Two small domains, one suite.
 */
@WithMockUser
class ResourcesAndPortabilityIntegrationTest extends AuroraIntegrationTest {

  private void manifestWithResources(String pkg, String resourcesBlock) throws IOException {
    writeRepoFile("packages/" + pkg + "/manifest.yml", """
        name: %s
        title: %s
        description: a thing
        category: productivity
        depends_on:
          - core
        %s
        """.formatted(pkg, pkg, resourcesBlock));
  }

  @Nested
  @DisplayName("resource ceilings")
  class Resources {

    @Test
    void reports_what_the_manifest_declares() throws Exception {
      manifestWithResources("ai", """
          resources:
            mem_limit_mb: 10240
            cpus: 3.0
          """);

      mvc.perform(get("/api/packages/ai/resources"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.package").value("ai"))
          .andExpect(jsonPath("$.defaultMemLimitMb").value(10240))
          .andExpect(jsonPath("$.defaultCpus").value(3.0))
          .andExpect(jsonPath("$.memLimitMb").doesNotExist())
          .andExpect(jsonPath("$.cpus").doesNotExist());
    }

    @Test
    void reports_an_uncapped_package_as_null_rather_than_zero() throws Exception {
      // Zero would read as "capped at nothing", which is the opposite of
      // what an absent resources block means.
      manifestWithResources("notes", "");

      mvc.perform(get("/api/packages/notes/resources"))
          .andExpect(jsonPath("$.defaultMemLimitMb").doesNotExist())
          .andExpect(jsonPath("$.defaultCpus").doesNotExist());
    }

    @Test
    void remembers_an_override_without_touching_the_manifest() throws Exception {
      manifestWithResources("ai", """
          resources:
            mem_limit_mb: 10240
            cpus: 3.0
          """);
      String before = readRepoFile("packages/ai/manifest.yml");

      mvc.perform(put("/api/packages/ai/resources").contentType(MediaType.APPLICATION_JSON)
              .content("{\"memLimitMb\":4096,\"cpus\":1.5}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.memLimitMb").value(4096))
          .andExpect(jsonPath("$.cpus").value(1.5))
          .andExpect(jsonPath("$.defaultMemLimitMb").value(10240));

      mvc.perform(get("/api/packages/ai/resources"))
          .andExpect(jsonPath("$.memLimitMb").value(4096));

      // The manifest is git-tracked and belongs to the package; a local
      // ceiling does not go in it.
      org.assertj.core.api.Assertions.assertThat(readRepoFile("packages/ai/manifest.yml"))
          .isEqualTo(before);
    }

    @Test
    void clearing_both_fields_restores_the_shipped_defaults() throws Exception {
      manifestWithResources("ai", """
          resources:
            mem_limit_mb: 10240
            cpus: 3.0
          """);
      mvc.perform(put("/api/packages/ai/resources").contentType(MediaType.APPLICATION_JSON)
          .content("{\"memLimitMb\":4096,\"cpus\":1.5}"));

      mvc.perform(put("/api/packages/ai/resources").contentType(MediaType.APPLICATION_JSON)
              .content("{\"memLimitMb\":null,\"cpus\":null}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.memLimitMb").doesNotExist())
          .andExpect(jsonPath("$.defaultMemLimitMb").value(10240));
    }

    @Test
    void refuses_a_ceiling_too_small_to_start_anything() throws Exception {
      manifestWithResources("ai", "");

      mvc.perform(put("/api/packages/ai/resources").contentType(MediaType.APPLICATION_JSON)
              .content("{\"memLimitMb\":8,\"cpus\":null}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    void refuses_a_cpu_ceiling_of_zero() throws Exception {
      manifestWithResources("ai", "");

      mvc.perform(put("/api/packages/ai/resources").contentType(MediaType.APPLICATION_JSON)
              .content("{\"memLimitMb\":null,\"cpus\":0}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    void refuses_a_package_name_that_could_walk_the_filesystem() throws Exception {
      // The name comes off the URL and becomes part of a path.
      mvc.perform(get("/api/packages/{name}/resources", "..%2F..%2Fetc"))
          .andExpect(status().isBadRequest());
    }

    @Test
    void a_package_with_no_manifest_reports_uncapped_rather_than_failing() throws Exception {
      mvc.perform(get("/api/packages/ghost/resources"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.defaultMemLimitMb").doesNotExist());
    }
  }

  @Nested
  @DisplayName("taking the settings with you")
  class Portability {

    @Test
    void exports_the_shape_the_frontend_reads() throws Exception {
      mvc.perform(get("/api/system/export"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.version").value(1))
          .andExpect(jsonPath("$.exportedAt").isNotEmpty())
          .andExpect(jsonPath("$.enabledPackages").isArray())
          .andExpect(jsonPath("$.settings").exists());
    }

    @Test
    void never_exports_anything_secret() throws Exception {
      writeRepoFile("packages/media/.env", "DB_PASSWORD=hunter2\nAPI_KEY=abcdef\n");

      String body = mvc.perform(get("/api/system/export"))
          .andReturn().getResponse().getContentAsString();

      org.assertj.core.api.Assertions.assertThat(body)
          .as("this file is meant to be safe to keep in an ordinary backup")
          .doesNotContain("hunter2")
          .doesNotContain("abcdef")
          .doesNotContain("PASSWORD");
    }

    @Test
    void previews_by_default_and_changes_nothing() throws Exception {
      String before = readRepoFile(".state.yml");

      mvc.perform(post("/api/system/import").param("preview", "1")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"version\":1,\"enabledPackages\":[\"core\",\"media\",\"notes\"]}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.preview").value(true))
          .andExpect(jsonPath("$.applied[0]").value("3 apps enabled"));

      org.assertj.core.api.Assertions.assertThat(readRepoFile(".state.yml")).isEqualTo(before);
    }

    @Test
    void applies_only_when_asked_to() throws Exception {
      mvc.perform(post("/api/system/import")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"version\":1,\"enabledPackages\":[\"core\",\"media\"]}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.preview").value(false));

      org.assertj.core.api.Assertions.assertThat(readRepoFile(".state.yml"))
          .contains("media");
    }

    @Test
    void always_says_that_secrets_still_need_doing_by_hand() throws Exception {
      // Every time, preview or not. Nobody should walk away from an import
      // believing the box is ready when every .env is still empty.
      mvc.perform(post("/api/system/import").param("preview", "1")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"version\":1,\"enabledPackages\":[]}"))
          .andExpect(jsonPath("$.skipped[0]").value(
              org.hamcrest.Matchers.containsString("secrets")));
    }

    @Test
    void refuses_a_file_from_a_different_version() throws Exception {
      mvc.perform(post("/api/system/import").contentType(MediaType.APPLICATION_JSON)
              .content("{\"version\":99,\"enabledPackages\":[]}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    void refuses_a_file_with_no_version_at_all() throws Exception {
      mvc.perform(post("/api/system/import").contentType(MediaType.APPLICATION_JSON)
              .content("{\"enabledPackages\":[]}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    void ignores_junk_in_the_package_list_rather_than_writing_it() throws Exception {
      mvc.perform(post("/api/system/import").param("preview", "1")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"version\":1,\"enabledPackages\":[\"core\",null,\"\",42]}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.applied[0]").value("1 apps enabled"));
    }
  }
}
