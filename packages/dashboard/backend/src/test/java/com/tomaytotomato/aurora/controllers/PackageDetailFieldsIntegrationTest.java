package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.support.AuroraIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;

import java.io.IOException;
import java.nio.file.Files;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The app detail page renders a README, a vhost list, an env-var table and
 * Source/Docs buttons. Until now every one of those came from the MSW
 * fixtures only: {@code openapi.yaml} documented them on
 * {@code PackageDetail}, the frontend read them, and the backend served
 * none of them. Against a real box the page was substantially emptier
 * than the one that got reviewed, and its two link buttons pointed
 * nowhere.
 *
 * <p>Written against the spec rather than the implementation: every
 * assertion here is a field {@code PackageDetail} already promises, and
 * the {@code OpenApiConformance} check wired into
 * {@link AuroraIntegrationTest} validates the whole body besides.
 */
@WithMockUser
class PackageDetailFieldsIntegrationTest extends AuroraIntegrationTest {

  /**
   * Written here rather than committed under {@code fake-repo}: a
   * {@code .env.example} fixture is more readable at the point that
   * asserts on it, and the parser's whole job is to tell these four
   * shapes apart.
   */
  @BeforeEach
  void seedEnvExample() throws IOException {
    Files.writeString(REPO_ROOT.resolve("packages/notes/.env.example"), """
        # aurora.local / packages/notes / .env
        #
        # Fixture for the env-spec parser.

        TZ=Europe/London

        # Bearer token Silverbullet requires for the sync API.
        NOTES_AUTH_TOKEN=

        # Where the notes space is mounted inside the container.
        NOTES_SPACE_PATH=/space
        """);
  }

  @Nested
  class Readme {

    @Test
    @DisplayName("serves packages/<name>/README.md verbatim, heading included")
    void serves_the_readme_file() throws Exception {
      mvc.perform(get("/api/packages/notes"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.readme").value(containsString("# Notes")))
          .andExpect(jsonPath("$.readme").value(containsString("self-hosted notebook")));
    }

    @Test
    @DisplayName("a package with no README.md omits the field rather than sending null")
    void absent_readme_is_omitted() throws Exception {
      // PackageDetail types readme as a plain string, so a null would fail
      // the spec check. Omission is the only shape that validates.
      mvc.perform(get("/api/packages/photos"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.readme").doesNotExist());
    }
  }

  @Nested
  class Vhosts {

    @Test
    @DisplayName("vhosts are fully qualified against the configured domain")
    void vhosts_are_fully_qualified() throws Exception {
      // notes declares vhosts: [notes, drafts] and its caddy.snippet adds
      // 'legacy'; the discovered set is the union of both, and the page
      // needs hostnames a browser can use, not bare labels.
      mvc.perform(get("/api/packages/notes"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.vhosts", containsInAnyOrder(
              "notes.aurora.local", "drafts.aurora.local", "legacy.aurora.local")));
    }

    @Test
    @DisplayName("a package serving no vhosts gets an empty array, not a missing one")
    void no_vhosts_is_an_empty_array() throws Exception {
      // storage has neither a vhosts: block nor a caddy.snippet. The
      // frontend renders "none" from an empty array; the spec types
      // vhosts as an array, so null would fail validation.
      mvc.perform(get("/api/packages/storage"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.vhosts").isArray())
          .andExpect(jsonPath("$.vhosts").isEmpty());
    }
  }

  @Nested
  class EnvVars {

    @Test
    @DisplayName("env specs carry the key, the example value and the preceding comment")
    void parses_key_example_and_comment() throws Exception {
      mvc.perform(get("/api/packages/notes"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.envVars[?(@.key=='NOTES_SPACE_PATH')].example").value("/space"))
          // A filtered path yields an array, so the matcher has to expect
          // one rather than a bare string.
          .andExpect(jsonPath("$.envVars[?(@.key=='NOTES_SPACE_PATH')].comment")
              .value(containsInAnyOrder(containsString("mounted inside the container"))));
    }

    @Test
    @DisplayName("a token is a secret; TZ is not, despite sitting in the same file")
    void tells_secrets_from_configuration() throws Exception {
      mvc.perform(get("/api/packages/notes"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.envVars[?(@.key=='NOTES_AUTH_TOKEN')].secret").value(true))
          .andExpect(jsonPath("$.envVars[?(@.key=='TZ')].secret").value(false));
    }

    @Test
    @DisplayName("required mirrors the manifest's required_env, not the file")
    void required_comes_from_the_manifest() throws Exception {
      // notes declares required_env: [NOTES_AUTH_TOKEN]. The .env.example
      // has no way to express "required", so the manifest is the source.
      mvc.perform(get("/api/packages/notes"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.envVars[?(@.key=='NOTES_AUTH_TOKEN')].required").value(true))
          .andExpect(jsonPath("$.envVars[?(@.key=='NOTES_SPACE_PATH')].required").value(false));
    }

    @Test
    @DisplayName("the spec list never carries values, only what each key is for")
    void never_serves_values() throws Exception {
      // A populated .env sitting beside the example must not leak through
      // this endpoint: values come from GET /packages/{name}/env, which
      // masks secrets unless reveal=1.
      Files.writeString(REPO_ROOT.resolve("packages/notes/.env"),
          "NOTES_AUTH_TOKEN=hunter2-the-real-one\n");

      mvc.perform(get("/api/packages/notes"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.envVars[?(@.key=='NOTES_AUTH_TOKEN')].value").doesNotExist())
          .andExpect(content().string(not(containsString("hunter2-the-real-one"))));
    }
  }

  @Nested
  class Backup {

    @Test
    @DisplayName("serves the manifest's backup block, paths and pre-snapshot actions")
    void serves_the_backup_block() throws Exception {
      mvc.perform(get("/api/packages/photos"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.backup.paths", containsInAnyOrder("data/photos/library")))
          .andExpect(jsonPath("$.backup.before[0].kind").value("postgres-dump"))
          .andExpect(jsonPath("$.backup.before[0].container").value("immich-postgres"))
          .andExpect(jsonPath("$.backup.before[0].description")
              .value(containsString("restores cleanly")));
    }

    @Test
    @DisplayName("a package that declares no backup block omits the field")
    void absent_backup_is_omitted() throws Exception {
      // The spec would accept an explicit null here (backup is
      // oneOf [PackageBackupSpec, null]) but not for readme, which is a
      // plain string. One omit-when-absent rule covers both rather than
      // two rules that differ for no reason the caller can see.
      mvc.perform(get("/api/packages/notes"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.backup").doesNotHaveJsonPath());
    }
  }

  @Nested
  class UpstreamLinks {

    @Test
    @DisplayName("Source and Docs buttons get their hrefs from the manifest")
    void serves_the_manifest_links() throws Exception {
      mvc.perform(get("/api/packages/notes"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.sourceUrl").value("https://github.com/silverbulletmd/silverbullet"))
          .andExpect(jsonPath("$.homepageUrl").value("https://silverbullet.md"));
    }

    @Test
    @DisplayName("upstream links appear on the list endpoint too, per PackageSummary")
    void links_are_summary_fields() throws Exception {
      // openapi.yaml puts sourceUrl/homepageUrl on PackageSummary, not on
      // PackageDetail, so the catalogue can render them without a detail
      // fetch per card.
      mvc.perform(get("/api/packages"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[?(@.name=='notes')].sourceUrl")
              .value(containsInAnyOrder("https://github.com/silverbulletmd/silverbullet")));
    }
  }

  @Nested
  class ListStaysASummary {

    @Test
    @DisplayName("the list endpoint does not carry the detail-only fields")
    void list_omits_detail_fields() throws Exception {
      // Not tidiness: OpenApiConformance fails any response carrying a
      // property its schema does not document, and PackageSummary
      // documents none of these four. Serving them here would mean
      // growing the known-undocumented-fields registry, which is the one
      // thing that must not happen to pay for this feature.
      mvc.perform(get("/api/packages"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].readme").doesNotExist())
          .andExpect(jsonPath("$[0].vhosts").doesNotExist())
          .andExpect(jsonPath("$[0].envVars").doesNotExist())
          .andExpect(jsonPath("$[0].backup").doesNotExist());
    }
  }
}
