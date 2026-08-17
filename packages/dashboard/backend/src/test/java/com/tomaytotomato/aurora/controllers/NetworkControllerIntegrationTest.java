package com.tomaytotomato.aurora.controllers;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import com.tomaytotomato.aurora.support.AuroraIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code /api/packages/{name}/network} against a real repository tree. */
@WithMockUser
class NetworkControllerIntegrationTest extends AuroraIntegrationTest {

  @Autowired
  DockerClient dockerClient;

  private static Container container(String name, String project) {
    Container c = Mockito.mock(Container.class);
    Mockito.when(c.getId()).thenReturn("id-" + name);
    Mockito.when(c.getNames()).thenReturn(new String[] { "/" + name });
    Mockito.when(c.getImage()).thenReturn("img:1");
    Mockito.when(c.getState()).thenReturn("running");
    Mockito.when(c.getStatus()).thenReturn("Up 2 seconds (health: starting)");
    Map<String, String> labels = new HashMap<>();
    labels.put("com.docker.compose.project", project);
    Mockito.when(c.getLabels()).thenReturn(labels);
    return c;
  }

  private void stubContainers(Container... containers) {
    @SuppressWarnings("unchecked")
    ListContainersCmd cmd = dockerClient.listContainersCmd();
    Mockito.when(cmd.withShowAll(true).exec()).thenReturn(List.of(containers));
  }

  @BeforeEach
  void seedNotes() throws IOException {
    writeRepoFile("packages/notes/manifest.yml", """
        name: notes
        title: Notes (SilverBullet)
        description: fixture
        category: productivity
        depends_on: [core]
        recommends: []
        profiles: {}
        ports:
          - {port: 3030, proto: tcp, description: SilverBullet web UI}
        requires:
          min_ram_mb: 256
          min_disk_gb: 1
          host_roles: []
        required_env: []
        post_install_notes: ""
        probe:
          kind: docker
          container: silverbullet
        """);
    writeRepoFile("packages/notes/compose.yml", """
        name: aurora-notes
        services:
          silverbullet:
            image: ghcr.io/silverbulletmd/silverbullet:latest
            container_name: silverbullet
        """);
  }

  @Test
  void a_running_package_reports_direct_mode_and_its_real_container() throws Exception {
    stubContainers(container("silverbullet", "aurora"));

    mvc.perform(get("/api/packages/notes/network"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.package").value("notes"))
        .andExpect(jsonPath("$.mode").value("direct"))
        .andExpect(jsonPath("$.gateway").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.containers[0]").value("silverbullet"))
        .andExpect(jsonPath("$.publishedPorts[0]").value(3030));
  }

  @Test
  void locked_is_true_with_a_reason_because_the_toggle_is_not_wired_up_yet() throws Exception {
    stubContainers(container("silverbullet", "aurora"));

    mvc.perform(get("/api/packages/notes/network"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.locked").value(true))
        .andExpect(jsonPath("$.lockedReason").isNotEmpty());
  }

  @Test
  void an_app_sharing_a_gateways_namespace_reports_vpn_mode() throws Exception {
    writeRepoFile("packages/notes/compose.yml", """
        name: aurora-notes
        services:
          silverbullet:
            image: ghcr.io/silverbulletmd/silverbullet:latest
            container_name: silverbullet
            network_mode: "service:gluetun"
        """);
    stubContainers(container("silverbullet", "aurora"), container("gluetun", "aurora-privacy"));

    mvc.perform(get("/api/packages/notes/network"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("vpn"))
        .andExpect(jsonPath("$.gateway").value("gluetun"))
        .andExpect(jsonPath("$.gatewayHealthy").exists());
  }

  @Test
  void unknown_package_is_404() throws Exception {
    mvc.perform(get("/api/packages/not-a-real-package/network"))
        .andExpect(status().isNotFound());
  }

  @Test
  void put_is_a_409_not_a_404_or_a_silent_no_op() throws Exception {
    mvc.perform(put("/api/packages/notes/network")
            .contentType("application/json")
            .content("{\"mode\":\"vpn\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void put_against_an_unknown_package_is_still_404() throws Exception {
    mvc.perform(put("/api/packages/not-a-real-package/network")
            .contentType("application/json")
            .content("{\"mode\":\"vpn\"}"))
        .andExpect(status().isNotFound());
  }
}
