package com.tomaytotomato.aurora.controllers;

import com.github.dockerjava.api.model.Container;
import com.tomaytotomato.aurora.services.DockerEventService;
import com.tomaytotomato.aurora.services.DockerService;
import com.tomaytotomato.aurora.services.PackagesService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B3-followup (iter-16): {@code GET /api/containers?package=<name>}
 * request handling at the controller boundary. The actual project-label /
 * legacy-fallback matching now lives in
 * {@link DockerService#containersForPackage} (see
 * {@code DockerServiceTests} for that behaviour) — these tests only cover
 * what the controller itself is responsible for:
 *
 * <ul>
 *   <li>no {@code package} query → delegates to
 *       {@link DockerService#listProjectContainers()} unfiltered;</li>
 *   <li>a {@code package} query → delegates to
 *       {@link DockerService#containersForPackage} with the right
 *       expected-container name (the manifest's {@code probe.container},
 *       defaulting to the package name);</li>
 *   <li>malformed package name → 400 without hitting docker;</li>
 *   <li>the JSON shape of whatever DockerService hands back.</li>
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
    return mvc(docker, Mockito.mock(PackagesService.class));
  }

  private static MockMvc mvc(DockerService docker, PackagesService packages) {
    return MockMvcBuilders
        .standaloneSetup(new ContainersController(docker, Mockito.mock(DockerEventService.class), packages))
        .build();
  }

  @Test
  void unfiltered_call_returns_everything_from_listProjectContainers() throws Exception {
    DockerService docker = Mockito.mock(DockerService.class);
    Container aurora = container("aurora", "aurora", "aurora");
    Container sonarr = container("aurora-media-sonarr", "aurora-media", "sonarr");
    Mockito.when(docker.listProjectContainers()).thenReturn(List.of(aurora, sonarr));
    mvc(docker).perform(get("/api/containers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
    Mockito.verify(docker, Mockito.never()).containersForPackage(Mockito.any(), Mockito.any());
  }

  @Test
  void filtered_call_delegates_to_containersForPackage_with_the_manifest_probe_container() throws Exception {
    DockerService docker = Mockito.mock(DockerService.class);
    Container sonarr = container("aurora-media-sonarr", "aurora-media", "sonarr");
    Mockito.when(docker.containersForPackage("media", "media")).thenReturn(List.of(sonarr));

    PackagesService packages = Mockito.mock(PackagesService.class);
    Mockito.when(packages.readProbe("media")).thenReturn(Map.of());

    mvc(docker, packages).perform(get("/api/containers").param("package", "media"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].service").value("sonarr"));
  }

  @Test
  void filtered_call_uses_the_manifests_declared_probe_container_not_just_the_package_name() throws Exception {
    // notes' manifest declares probe.container: silverbullet (the compose
    // service is named "silverbullet", not "notes") — the controller must
    // pass that through rather than always asking for the package name.
    DockerService docker = Mockito.mock(DockerService.class);
    Container silverbullet = container("silverbullet", "aurora-notes", "silverbullet");
    Mockito.when(docker.containersForPackage("notes", "silverbullet")).thenReturn(List.of(silverbullet));

    PackagesService packages = Mockito.mock(PackagesService.class);
    Mockito.when(packages.readProbe("notes")).thenReturn(Map.of("kind", "docker", "container", "silverbullet"));

    mvc(docker, packages).perform(get("/api/containers").param("package", "notes"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].names[0]").value("/silverbullet"));
  }

  @Test
  void filtered_call_defaults_the_expected_container_to_the_package_name_when_manifest_has_no_probe() throws Exception {
    DockerService docker = Mockito.mock(DockerService.class);
    Mockito.when(docker.containersForPackage(eq("photos"), eq("photos"))).thenReturn(List.of());

    PackagesService packages = Mockito.mock(PackagesService.class);
    Mockito.when(packages.readProbe("photos")).thenReturn(Map.of());

    mvc(docker, packages).perform(get("/api/containers").param("package", "photos"))
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
