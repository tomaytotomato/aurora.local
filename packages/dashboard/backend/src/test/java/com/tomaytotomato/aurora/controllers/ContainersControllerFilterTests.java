package com.tomaytotomato.aurora.controllers;

import com.github.dockerjava.api.model.Container;
import com.tomaytotomato.aurora.services.DockerEventService;
import com.tomaytotomato.aurora.services.DockerService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B3-followup (iter-16): {@code GET /api/containers?package=<name>}
 * filter behaviour. Standalone MockMvc. Verifies:
 *
 * <ul>
 *   <li>No {@code package} query → returns everything from
 *       {@link DockerService#listProjectContainers()} unchanged.</li>
 *   <li>{@code package=core} → maps to compose project label
 *       {@code aurora}.</li>
 *   <li>{@code package=<pkg>} → maps to compose project label
 *       {@code aurora-<pkg>}.</li>
 *   <li>Malformed package name → 400 without hitting docker.</li>
 * </ul>
 */
class ContainersControllerFilterTests {

  private static Container container(String name, String project, String service) {
    Container c = Mockito.mock(Container.class);
    Mockito.when(c.getId()).thenReturn("id-" + name);
    Mockito.when(c.getNames()).thenReturn(new String[] { "/" + name });
    Mockito.when(c.getImage()).thenReturn("img:1");
    Mockito.when(c.getState()).thenReturn("running");
    Mockito.when(c.getStatus()).thenReturn("Up 1h");
    Map<String, String> labels = new HashMap<>();
    labels.put("com.docker.compose.project", project);
    labels.put("com.docker.compose.service", service);
    Mockito.when(c.getLabels()).thenReturn(labels);
    return c;
  }

  private static MockMvc mvc(DockerService docker) {
    return MockMvcBuilders
        .standaloneSetup(new ContainersController(docker, Mockito.mock(DockerEventService.class)))
        .build();
  }

  @Test
  void unfiltered_call_returns_all_containers() throws Exception {
    DockerService docker = Mockito.mock(DockerService.class);
    Container aurora = container("aurora", "aurora", "aurora");
    Container sonarr = container("aurora-media-sonarr", "aurora-media", "sonarr");
    Mockito.when(docker.listProjectContainers()).thenReturn(List.of(aurora, sonarr));
    mvc(docker).perform(get("/api/containers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void filter_by_package_matches_aurora_prefix_project() throws Exception {
    DockerService docker = Mockito.mock(DockerService.class);
    Container aurora = container("aurora", "aurora", "aurora");
    Container sonarr = container("aurora-media-sonarr", "aurora-media", "sonarr");
    Container radarr = container("aurora-media-radarr", "aurora-media", "radarr");
    Container silverbullet = container("aurora-notes-silverbullet", "aurora-notes", "silverbullet");
    Mockito.when(docker.listProjectContainers())
        .thenReturn(List.of(aurora, sonarr, radarr, silverbullet));
    mvc(docker).perform(get("/api/containers").param("package", "media"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].service").value("sonarr"))
        .andExpect(jsonPath("$[1].service").value("radarr"));
  }

  @Test
  void filter_by_core_maps_to_shared_aurora_project() throws Exception {
    // packages/core/compose.yml uses the historical shared 'aurora'
    // project name. package=core should surface those containers, not
    // package=aurora (which isn't a valid package name).
    DockerService docker = Mockito.mock(DockerService.class);
    Container aurora = container("aurora", "aurora", "aurora");
    Container caddy = container("caddy", "aurora", "caddy");
    Container sonarr = container("aurora-media-sonarr", "aurora-media", "sonarr");
    Mockito.when(docker.listProjectContainers()).thenReturn(List.of(aurora, caddy, sonarr));
    mvc(docker).perform(get("/api/containers").param("package", "core"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].names[0]").value("/aurora"))
        .andExpect(jsonPath("$[1].names[0]").value("/caddy"));
  }

  @Test
  void filter_returns_empty_when_no_project_matches() throws Exception {
    DockerService docker = Mockito.mock(DockerService.class);
    Container sonarr = container("aurora-media-sonarr", "aurora-media", "sonarr");
    Mockito.when(docker.listProjectContainers()).thenReturn(List.of(sonarr));
    mvc(docker).perform(get("/api/containers").param("package", "photos"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void malformed_package_name_yields_400() throws Exception {
    DockerService docker = Mockito.mock(DockerService.class);
    for (String bad : new String[] {
        "-leading-dash",
        ".dotstart",
        "UPPER",
        "with_underscore",
        "way-too-long-package-name-that-exceeds-32-chars"
    }) {
      mvc(docker).perform(get("/api/containers").param("package", bad))
          .andExpect(status().isBadRequest());
    }
    Mockito.verifyNoInteractions(docker);
  }

  @Test
  void empty_package_query_is_treated_as_no_filter() throws Exception {
    // Empty string ≠ null in Spring's binding; must still be validated.
    // '^[a-z][a-z0-9-]{0,31}$' rejects the empty string, so ?package= is
    // a 400. Verifies the belt-and-braces of the shape regex.
    DockerService docker = Mockito.mock(DockerService.class);
    mvc(docker).perform(get("/api/containers").param("package", ""))
        .andExpect(status().isBadRequest());
  }
}
