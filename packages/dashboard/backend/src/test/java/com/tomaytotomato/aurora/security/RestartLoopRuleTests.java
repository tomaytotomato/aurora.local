package com.tomaytotomato.aurora.security;

import com.github.dockerjava.api.model.Container;
import com.tomaytotomato.aurora.domain.SecurityFinding;
import com.tomaytotomato.aurora.services.DockerService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RestartLoopRuleTests {

  private static Container container(String name, String state, String status) {
    Container c = Mockito.mock(Container.class);
    Mockito.when(c.getNames()).thenReturn(new String[] { "/" + name });
    Mockito.when(c.getState()).thenReturn(state);
    Mockito.when(c.getStatus()).thenReturn(status);
    return c;
  }

  private static DockerService dockerWith(List<Container> containers) {
    DockerService d = Mockito.mock(DockerService.class);
    Mockito.when(d.listProjectContainers()).thenReturn(containers);
    return d;
  }

  @Test
  void restarting_container_emits_finding_with_count() {
    RestartLoopRule rule = new RestartLoopRule(dockerWith(List.of(
        container("authelia", "restarting", "Restarting (7) 12 seconds ago")
    )));
    List<SecurityFinding> findings = rule.evaluate();

    assertThat(findings).hasSize(1);
    SecurityFinding f = findings.get(0);
    assertThat(f.id()).isEqualTo("restart_loop:authelia");
    assertThat(f.severity()).isEqualTo(SecurityFinding.MEDIUM);
    assertThat(f.title()).isEqualTo("Authelia is restart-looping");
    assertThat(f.description()).contains("7 times");
    assertThat(f.description()).contains("docker logs authelia");
  }

  @Test
  void singular_count_reads_naturally() {
    RestartLoopRule rule = new RestartLoopRule(dockerWith(List.of(
        container("stalwart", "restarting", "Restarting (1) 3 seconds ago")
    )));
    assertThat(rule.evaluate().get(0).description())
        .contains("once");
  }

  @Test
  void running_container_emits_nothing() {
    RestartLoopRule rule = new RestartLoopRule(dockerWith(List.of(
        container("caddy", "running", "Up 3 hours (healthy)")
    )));
    assertThat(rule.evaluate()).isEmpty();
  }

  @Test
  void exited_container_is_not_a_restart_loop() {
    // Exited != restarting. That's a different failure mode; a separate
    // rule can own it if we ever need one.
    RestartLoopRule rule = new RestartLoopRule(dockerWith(List.of(
        container("memos", "exited", "Exited (1) 30 seconds ago")
    )));
    assertThat(rule.evaluate()).isEmpty();
  }

  @Test
  void aurora_owner_containers_are_exempt() {
    // We can't honestly emit a finding about ourselves while responding
    // to a request \u2014 by the time this evaluate() runs, we're up. The
    // exemption also stops a bootstrap-loop from producing a finding
    // nobody can see anyway.
    RestartLoopRule rule = new RestartLoopRule(dockerWith(List.of(
        container("aurora", "restarting", "Restarting (2) 5 seconds ago")
    )));
    assertThat(rule.evaluate()).isEmpty();
  }

  @Test
  void docker_failure_is_swallowed_returns_empty() {
    DockerService d = Mockito.mock(DockerService.class);
    Mockito.when(d.listProjectContainers()).thenThrow(new RuntimeException("docker unavailable"));
    RestartLoopRule rule = new RestartLoopRule(d);
    assertThat(rule.evaluate()).isEmpty();
  }

  @Test
  void extractRestartCount_parses_docker_status_string() {
    assertThat(RestartLoopRule.extractRestartCount("Restarting (7) 12 seconds ago")).isEqualTo(7);
    assertThat(RestartLoopRule.extractRestartCount("Restarting (0) just now")).isEqualTo(0);
    assertThat(RestartLoopRule.extractRestartCount("Up 3 hours")).isEqualTo(0);
    assertThat(RestartLoopRule.extractRestartCount(null)).isEqualTo(0);
  }
}
