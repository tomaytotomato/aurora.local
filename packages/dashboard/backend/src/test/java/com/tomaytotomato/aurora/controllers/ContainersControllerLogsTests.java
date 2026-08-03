package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.services.DockerEventService;
import com.tomaytotomato.aurora.services.DockerService;
import com.tomaytotomato.aurora.services.DockerService.LogLine;
import com.tomaytotomato.aurora.services.DockerService.LogTail;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B3 (v0.3): {@code GET /api/containers/{id}/logs} contract. Standalone
 * MockMvc, matches HealthControllerTests / MetricsControllerTests
 * pattern. Docker interactions are mocked at the {@link DockerService}
 * boundary.
 */
class ContainersControllerLogsTests {

  private static MockMvc mvc(DockerService docker) {
    DockerEventService events = Mockito.mock(DockerEventService.class);
    return MockMvcBuilders.standaloneSetup(new ContainersController(docker, events)).build();
  }

  @Test
  void returns_tail_snapshot_for_valid_container() throws Exception {
    DockerService docker = Mockito.mock(DockerService.class);
    when(docker.inspectContainer("aurora-media-sonarr")).thenReturn(Optional.of("abc123"));
    when(docker.tailLogs(eq("aurora-media-sonarr"), eq(200), any(Duration.class)))
        .thenReturn(new LogTail(List.of(
            new LogLine("2026-08-03T08:15:00Z", "stdout", "boot: starting"),
            new LogLine("2026-08-03T08:15:01Z", "stderr", "warn: slow disk"),
            new LogLine(null, "stdout", "no-ts line")
        ), false));

    mvc(docker).perform(get("/api/containers/aurora-media-sonarr/logs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.container_id").value("aurora-media-sonarr"))
        .andExpect(jsonPath("$.tail").value(200))
        .andExpect(jsonPath("$.truncated").value(false))
        .andExpect(jsonPath("$.lines.length()").value(3))
        .andExpect(jsonPath("$.lines[0].ts").value("2026-08-03T08:15:00Z"))
        .andExpect(jsonPath("$.lines[0].stream").value("stdout"))
        .andExpect(jsonPath("$.lines[0].line").value("boot: starting"))
        .andExpect(jsonPath("$.lines[1].stream").value("stderr"))
        .andExpect(jsonPath("$.lines[2].ts").doesNotExist());
  }

  @Test
  void surfaces_truncated_flag() throws Exception {
    DockerService docker = Mockito.mock(DockerService.class);
    when(docker.inspectContainer("c")).thenReturn(Optional.of("id"));
    when(docker.tailLogs(eq("c"), eq(200), any(Duration.class)))
        .thenReturn(new LogTail(List.of(new LogLine(null, "stdout", "x")), true));

    mvc(docker).perform(get("/api/containers/c/logs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.truncated").value(true));
  }

  @Test
  void empty_tail_is_valid() throws Exception {
    // A fresh container that hasn't logged anything → 200 with empty
    // lines[], not 404.
    DockerService docker = Mockito.mock(DockerService.class);
    when(docker.inspectContainer("fresh")).thenReturn(Optional.of("id"));
    when(docker.tailLogs(eq("fresh"), eq(200), any(Duration.class)))
        .thenReturn(new LogTail(List.of(), false));

    mvc(docker).perform(get("/api/containers/fresh/logs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lines.length()").value(0));
  }

  @Test
  void custom_tail_value_is_honoured() throws Exception {
    DockerService docker = Mockito.mock(DockerService.class);
    when(docker.inspectContainer("c")).thenReturn(Optional.of("id"));
    when(docker.tailLogs(eq("c"), eq(50), any(Duration.class)))
        .thenReturn(new LogTail(List.of(), false));

    mvc(docker).perform(get("/api/containers/c/logs").param("tail", "50"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tail").value(50));
  }

  @Test
  void returns_404_when_no_such_container() throws Exception {
    DockerService docker = Mockito.mock(DockerService.class);
    when(docker.inspectContainer("ghost")).thenReturn(Optional.empty());

    mvc(docker).perform(get("/api/containers/ghost/logs"))
        .andExpect(status().isNotFound());
    Mockito.verify(docker, Mockito.never()).tailLogs(any(), Mockito.anyInt(), any());
  }

  @Test
  void rejects_malformed_id() throws Exception {
    DockerService docker = Mockito.mock(DockerService.class);
    // Shapes that route cleanly but fail the id regex.
    for (String bad : new String[] {
        "-leading-dash",
        ".leading-dot",
        "_leading-underscore",
        "a$dollar",
        "a!bang",
        "a=eq",
    }) {
      mvc(docker).perform(get("/api/containers/" + bad + "/logs"))
          .andExpect(status().isBadRequest());
    }
    Mockito.verify(docker, Mockito.never()).inspectContainer(any());
  }

  @Test
  void rejects_tail_out_of_range() throws Exception {
    DockerService docker = Mockito.mock(DockerService.class);
    // inspectContainer never called on 400 fail-fast.
    for (int bad : new int[] { 0, -1, 2001, 10_000 }) {
      mvc(docker).perform(get("/api/containers/aurora/logs").param("tail", String.valueOf(bad)))
          .andExpect(status().isBadRequest());
    }
    Mockito.verifyNoInteractions(docker);
  }

  @Test
  void response_content_type_is_json() throws Exception {
    DockerService docker = Mockito.mock(DockerService.class);
    when(docker.inspectContainer("c")).thenReturn(Optional.of("id"));
    when(docker.tailLogs(eq("c"), eq(200), any(Duration.class)))
        .thenReturn(new LogTail(List.of(), false));

    mvc(docker).perform(get("/api/containers/c/logs"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/json"));
  }
}
