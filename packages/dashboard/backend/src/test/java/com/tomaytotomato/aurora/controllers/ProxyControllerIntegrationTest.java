package com.tomaytotomato.aurora.controllers;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Version;
import com.tomaytotomato.aurora.services.CaddySnippetService;
import com.tomaytotomato.aurora.support.AuroraIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/proxy} against a real repository tree and a real SQLite
 * {@code proxy_route} table.
 *
 * <p>The fixture repo (src/test/resources/fake-repo) already enables
 * {@code core}, {@code media} and {@code notes}. {@code media}'s
 * caddy.snippet declares {@code sonarr}/{@code radarr} as multi-line
 * blocks; {@code notes}'s declares {@code notes}/{@code legacy} as
 * single-line blocks — between them, every managed-route discovery shape
 * this suite cares about is already covered without adding new fixtures.
 */
@WithMockUser
class ProxyControllerIntegrationTest extends AuroraIntegrationTest {

  @Autowired
  DockerClient dockerClient;

  @Autowired
  CaddySnippetService caddySnippets;

  private static final String SNIPPET_PATH = "data/caddy/snippets/" + CaddySnippetService.CUSTOM_ROUTES_FILENAME;

  private Container containerNamed(String name) {
    Container c = Mockito.mock(Container.class);
    Mockito.when(c.getNames()).thenReturn(new String[] {"/" + name});
    Mockito.when(c.getLabels()).thenReturn(new HashMap<>());
    Mockito.when(c.getPorts()).thenReturn(new com.github.dockerjava.api.model.ContainerPort[0]);
    return c;
  }

  private void stubContainers(Container... containers) {
    Mockito.when(dockerClient.listContainersCmd().withShowAll(true).exec())
        .thenReturn(List.of(containers));
  }

  /**
   * {@code dockerClient} is the single {@code @Primary} bean the cached
   * Spring context shares across every {@code AuroraIntegrationTest}
   * subclass, so a stub left behind here would leak into whichever test
   * class runs next. Reset to the same shape {@link
   * com.tomaytotomato.aurora.TestDockerConfig} establishes at bean
   * creation (empty container list, a non-null version) before and after
   * this class runs, regardless of which of these tests customises it.
   */
  @BeforeEach
  void freshDockerStub() {
    Mockito.reset(dockerClient);
    Mockito.when(dockerClient.versionCmd().exec()).thenReturn(new Version());
    stubContainers();
  }

  @AfterEach
  void restoreDockerStub() {
    Mockito.reset(dockerClient);
    Mockito.when(dockerClient.versionCmd().exec()).thenReturn(new Version());
    stubContainers();
  }

  @Nested
  @DisplayName("GET /routes — managed routes")
  class ManagedRoutes {

    @Test
    void discovers_multiline_blocks_from_an_enabled_packages_snippet() throws Exception {
      mvc.perform(get("/api/proxy/routes"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[?(@.subdomain=='sonarr')].target").value("sonarr:8989"))
          .andExpect(jsonPath("$[?(@.subdomain=='sonarr')].package").value("media"))
          .andExpect(jsonPath("$[?(@.subdomain=='sonarr')].managed").value(true))
          .andExpect(jsonPath("$[?(@.subdomain=='sonarr')].vhost").value("sonarr.aurora.local"));
    }

    @Test
    void discovers_singleline_blocks_too() throws Exception {
      mvc.perform(get("/api/proxy/routes"))
          .andExpect(jsonPath("$[?(@.subdomain=='notes')].target").value("silverbullet:3000"))
          .andExpect(jsonPath("$[?(@.subdomain=='legacy')].target").value("silverbullet:3000"))
          .andExpect(jsonPath("$[?(@.subdomain=='notes')].package").value("notes"));
    }

    @Test
    void ignores_commented_out_vhost_lines() throws Exception {
      // media's caddy.snippet has a commented-out "zombie" vhost.
      mvc.perform(get("/api/proxy/routes"))
          .andExpect(jsonPath("$[?(@.subdomain=='zombie')]").isEmpty());
    }

    @Test
    void a_managed_route_has_no_createdAt() throws Exception {
      mvc.perform(get("/api/proxy/routes"))
          .andExpect(jsonPath("$[?(@.subdomain=='sonarr')].createdAt").value((Object) null));
    }

    @Test
    void a_disabled_packages_snippet_contributes_nothing() throws Exception {
      writeRepoFile(".state.yml", """
          bootstrap_version: 1
          enabled:
            - core
          profiles: []
          """);

      mvc.perform(get("/api/proxy/routes"))
          .andExpect(jsonPath("$[?(@.subdomain=='sonarr')]").isEmpty());
    }
  }

  @Nested
  @DisplayName("GET /targets")
  class Targets {

    @Test
    void reports_containers_with_their_ports_and_owning_package() throws Exception {
      Container c = containerNamed("silverbullet");
      Map<String, String> labels = new HashMap<>();
      labels.put("com.docker.compose.project", "aurora-notes");
      labels.put("com.docker.compose.project.config_files", "/repo/packages/notes/compose.yml");
      Mockito.when(c.getLabels()).thenReturn(labels);
      Mockito.when(c.getPorts()).thenReturn(new com.github.dockerjava.api.model.ContainerPort[] {
          new com.github.dockerjava.api.model.ContainerPort().withPrivatePort(3000)
      });
      stubContainers(c);

      mvc.perform(get("/api/proxy/targets"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].container").value("silverbullet"))
          .andExpect(jsonPath("$[0].ports[0]").value(3000))
          .andExpect(jsonPath("$[0].package").value("notes"));
    }

    @Test
    void an_empty_docker_daemon_reports_no_targets_rather_than_failing() throws Exception {
      mvc.perform(get("/api/proxy/targets"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(0));
    }
  }

  @Nested
  @DisplayName("POST /preview")
  class Preview {

    @Test
    void a_clean_name_and_a_visible_target_has_no_conflicts() throws Exception {
      Map<String, String> labels = new HashMap<>();
      labels.put("com.docker.compose.project", "aurora");
      Container c = containerNamed("calibre-web");
      Mockito.when(c.getLabels()).thenReturn(labels);
      stubContainers(c);

      mvc.perform(post("/api/proxy/preview").contentType(MediaType.APPLICATION_JSON)
              .content("{\"subdomain\":\"books\",\"target\":\"calibre-web:8083\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.vhost").value("books.aurora.local"))
          .andExpect(jsonPath("$.snippet").value(containsString("http://books.{$DOMAIN} {")))
          .andExpect(jsonPath("$.snippet").value(containsString("reverse_proxy calibre-web:8083")))
          .andExpect(jsonPath("$.conflicts.length()").value(0));
    }

    @Test
    void refuses_nothing_but_flags_a_reserved_name() throws Exception {
      mvc.perform(post("/api/proxy/preview").contentType(MediaType.APPLICATION_JSON)
              .content("{\"subdomain\":\"admin\",\"target\":\"aurora:8090\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.conflicts[?(@.kind=='reserved')].advisory").value(false));
    }

    @Test
    void flags_a_vhost_already_owned_by_a_managed_route() throws Exception {
      mvc.perform(post("/api/proxy/preview").contentType(MediaType.APPLICATION_JSON)
              .content("{\"subdomain\":\"sonarr\",\"target\":\"somewhereelse:80\"}"))
          .andExpect(jsonPath("$.conflicts[?(@.kind=='vhost-taken')].message")
              .value(org.hamcrest.Matchers.hasItem(containsString("media package"))))
          .andExpect(jsonPath("$.conflicts[?(@.kind=='vhost-taken')].advisory").value(false));
    }

    @Test
    void flags_a_target_aurora_cannot_currently_see_as_advisory() throws Exception {
      mvc.perform(post("/api/proxy/preview").contentType(MediaType.APPLICATION_JSON)
              .content("{\"subdomain\":\"newthing\",\"target\":\"ghost-container:1234\"}"))
          .andExpect(jsonPath("$.conflicts[?(@.kind=='target-unreachable')].advisory").value(true))
          .andExpect(jsonPath("$.conflicts[?(@.kind=='target-unreachable')].message")
              .value(org.hamcrest.Matchers.hasItem(containsString("ghost-container"))));
    }

    @Test
    void flags_a_name_already_published_by_mdns_as_advisory() throws Exception {
      writeRepoFile(".state.yml", """
          bootstrap_version: 1
          domain: aurora.local
          enabled:
            - core
            - media
            - notes
            - gallery
          profiles: []
          """);
      writeRepoFile("packages/gallery/manifest.yml", """
          name: gallery
          title: Gallery
          description: fixture
          category: photos
          vhosts: [gallery]
          """);

      mvc.perform(post("/api/proxy/preview").contentType(MediaType.APPLICATION_JSON)
              .content("{\"subdomain\":\"gallery\",\"target\":\"gallery-app:80\"}"))
          .andExpect(jsonPath("$.conflicts[?(@.kind=='mdns-alias')].advisory").value(true));
    }
  }

  @Nested
  @DisplayName("POST /routes — creating a hand-added route")
  class Create {

    @Test
    void creates_a_route_and_writes_the_custom_snippet() throws Exception {
      mvc.perform(post("/api/proxy/routes").contentType(MediaType.APPLICATION_JSON)
              .content("{\"subdomain\":\"blog\",\"target\":\"ghost:2368\"}"))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.startsWith("route-")))
          .andExpect(jsonPath("$.subdomain").value("blog"))
          .andExpect(jsonPath("$.vhost").value("blog.aurora.local"))
          .andExpect(jsonPath("$.managed").value(false))
          .andExpect(jsonPath("$.package").value((Object) null))
          .andExpect(jsonPath("$.createdAt").isNotEmpty());

      assertThat(repoFileExists(SNIPPET_PATH)).isTrue();
      String body = readRepoFile(SNIPPET_PATH);
      assertThat(body)
          .contains("http://blog.{$DOMAIN} {")
          .contains("reverse_proxy ghost:2368")
          .contains("https://blog.{$DOMAIN} {")
          .contains("tls internal");
    }

    @Test
    void refuses_a_reserved_name_with_409() throws Exception {
      mvc.perform(post("/api/proxy/routes").contentType(MediaType.APPLICATION_JSON)
              .content("{\"subdomain\":\"auth\",\"target\":\"authelia:9091\"}"))
          .andExpect(status().isConflict());

      assertThat(repoFileExists(SNIPPET_PATH)).isFalse();
    }

    @Test
    void refuses_a_name_a_package_already_owns_with_409() throws Exception {
      mvc.perform(post("/api/proxy/routes").contentType(MediaType.APPLICATION_JSON)
              .content("{\"subdomain\":\"sonarr\",\"target\":\"somewhereelse:80\"}"))
          .andExpect(status().isConflict());

      assertThat(repoFileExists(SNIPPET_PATH)).isFalse();
    }

    @Test
    void refuses_a_second_route_on_a_name_already_taken_by_hand() throws Exception {
      mvc.perform(post("/api/proxy/routes").contentType(MediaType.APPLICATION_JSON)
              .content("{\"subdomain\":\"blog\",\"target\":\"ghost:2368\"}"))
          .andExpect(status().isCreated());

      mvc.perform(post("/api/proxy/routes").contentType(MediaType.APPLICATION_JSON)
              .content("{\"subdomain\":\"blog\",\"target\":\"other:80\"}"))
          .andExpect(status().isConflict());
    }

    @Test
    void refuses_an_empty_subdomain_with_400() throws Exception {
      mvc.perform(post("/api/proxy/routes").contentType(MediaType.APPLICATION_JSON)
              .content("{\"subdomain\":\"\",\"target\":\"ghost:2368\"}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    void refuses_an_invalid_label_with_400() throws Exception {
      mvc.perform(post("/api/proxy/routes").contentType(MediaType.APPLICATION_JSON)
              .content("{\"subdomain\":\"not a label\",\"target\":\"ghost:2368\"}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    void adding_a_second_route_keeps_the_first_in_the_snippet() throws Exception {
      mvc.perform(post("/api/proxy/routes").contentType(MediaType.APPLICATION_JSON)
              .content("{\"subdomain\":\"blog\",\"target\":\"ghost:2368\"}"))
          .andExpect(status().isCreated());
      mvc.perform(post("/api/proxy/routes").contentType(MediaType.APPLICATION_JSON)
              .content("{\"subdomain\":\"wiki\",\"target\":\"wikijs:3000\"}"))
          .andExpect(status().isCreated());

      String body = readRepoFile(SNIPPET_PATH);
      assertThat(body).contains("blog.{$DOMAIN}").contains("wiki.{$DOMAIN}");
    }

    @Test
    void a_freshly_created_custom_route_survives_a_caddy_snippet_reconcile() throws Exception {
      // CaddySnippetService owns data/caddy/snippets/ and prunes anything
      // it doesn't recognise on every reconcile. The custom-routes file
      // must be an explicit exception, or the route would vanish from
      // Caddy's config within the next drift-guard tick.
      mvc.perform(post("/api/proxy/routes").contentType(MediaType.APPLICATION_JSON)
              .content("{\"subdomain\":\"blog\",\"target\":\"ghost:2368\"}"))
          .andExpect(status().isCreated());

      caddySnippets.reconcile();

      assertThat(repoFileExists(SNIPPET_PATH)).isTrue();
    }
  }

  @Nested
  @DisplayName("DELETE /routes/{id}")
  class Delete {

    private String createBlog() throws Exception {
      String body = mvc.perform(post("/api/proxy/routes").contentType(MediaType.APPLICATION_JSON)
              .content("{\"subdomain\":\"blog\",\"target\":\"ghost:2368\"}"))
          .andReturn().getResponse().getContentAsString();
      return com.jayway.jsonpath.JsonPath.read(body, "$.id");
    }

    @Test
    void removes_a_hand_added_route_and_rewrites_the_snippet() throws Exception {
      String id = createBlog();

      mvc.perform(delete("/api/proxy/routes/{id}", id))
          .andExpect(status().isNoContent());

      assertThat(repoFileExists(SNIPPET_PATH)).isFalse();
      mvc.perform(get("/api/proxy/routes"))
          .andExpect(jsonPath("$[?(@.subdomain=='blog')]").isEmpty());
    }

    @Test
    void refuses_to_remove_a_managed_route_with_409() throws Exception {
      mvc.perform(delete("/api/proxy/routes/{id}", "route-sonarr"))
          .andExpect(status().isConflict());

      mvc.perform(get("/api/proxy/routes"))
          .andExpect(jsonPath("$[?(@.subdomain=='sonarr')]").exists());
    }

    @Test
    void an_unknown_id_is_a_quiet_no_op() throws Exception {
      mvc.perform(delete("/api/proxy/routes/{id}", "route-does-not-exist"))
          .andExpect(status().isNoContent());
    }

    @Test
    void deleting_one_of_two_custom_routes_keeps_the_other_in_the_snippet() throws Exception {
      String blogId = createBlog();
      mvc.perform(post("/api/proxy/routes").contentType(MediaType.APPLICATION_JSON)
              .content("{\"subdomain\":\"wiki\",\"target\":\"wikijs:3000\"}"))
          .andExpect(status().isCreated());

      mvc.perform(delete("/api/proxy/routes/{id}", blogId)).andExpect(status().isNoContent());

      String body = readRepoFile(SNIPPET_PATH);
      assertThat(body).doesNotContain("blog.{$DOMAIN}").contains("wiki.{$DOMAIN}");
    }
  }
}
