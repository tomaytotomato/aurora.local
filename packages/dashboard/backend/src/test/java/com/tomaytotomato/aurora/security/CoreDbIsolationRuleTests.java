package com.tomaytotomato.aurora.security;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerConfig;
import com.tomaytotomato.aurora.domain.SecurityFinding;
import com.tomaytotomato.aurora.services.DockerService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runtime half of the core/non-core isolation boundary: a non-core
 * container wired to {@code core-db} is flagged; a core container on the
 * same DB is not (it is meant to be). See {@code CoreDbIsolationRule} and
 * docs/CORE_SHARED_SERVICES_PLAN.md.
 */
class CoreDbIsolationRuleTests {

  private static final String CFG_LABEL = "com.docker.compose.project.config_files";

  private static Container container(String name, String pkg) {
    Container c = Mockito.mock(Container.class);
    Mockito.when(c.getNames()).thenReturn(new String[] { "/" + name });
    String cfg = pkg == null
        ? null
        : "/home/bruce/aurora.local/packages/core/compose.yml,"
            + "/home/bruce/aurora.local/packages/" + pkg + "/compose.yml";
    Mockito.when(c.getLabels()).thenReturn(pkg == null ? Map.of() : Map.of(CFG_LABEL, cfg));
    return c;
  }

  private static DockerService dockerWith(List<Container> containers, Map<String, String[]> envByName) {
    DockerService d = Mockito.mock(DockerService.class);
    Mockito.when(d.listProjectContainers()).thenReturn(containers);
    for (var e : envByName.entrySet()) {
      InspectContainerResponse res = Mockito.mock(InspectContainerResponse.class);
      ContainerConfig cfg = Mockito.mock(ContainerConfig.class);
      Mockito.when(cfg.getEnv()).thenReturn(e.getValue());
      Mockito.when(res.getConfig()).thenReturn(cfg);
      Mockito.when(d.rawInspect(e.getKey())).thenReturn(res);
    }
    return d;
  }

  @Test
  void flags_a_non_core_container_using_core_db() {
    var d = dockerWith(
        List.of(container("immich-server", "photos")),
        Map.of("immich-server", new String[] { "DB_HOSTNAME=core-db", "TZ=UTC" }));
    List<SecurityFinding> findings = new CoreDbIsolationRule(d).evaluate();
    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).severity()).isEqualTo(SecurityFinding.MEDIUM);
    assertThat(findings.get(0).id()).isEqualTo("core_db_isolation:immich-server");
    assertThat(findings.get(0).title()).contains("photos");
    // Copy is for Sarah — no shell/jargon.
    assertThat(findings.get(0).description()).doesNotContain("docker").doesNotContain("sudo");
  }

  @Test
  void does_not_flag_a_core_container_on_core_db() {
    var d = dockerWith(
        List.of(container("authelia", "core")),
        Map.of("authelia", new String[] { "AUTHELIA_STORAGE_POSTGRES_ADDRESS=tcp://core-db:5432" }));
    assertThat(new CoreDbIsolationRule(d).evaluate()).isEmpty();
  }

  @Test
  void does_not_flag_a_non_core_container_with_its_own_db() {
    var d = dockerWith(
        List.of(container("paperless-db", "documents")),
        Map.of("paperless-db", new String[] { "POSTGRES_DB=paperless", "TZ=UTC" }));
    assertThat(new CoreDbIsolationRule(d).evaluate()).isEmpty();
  }

  @Test
  void ignores_a_container_with_no_known_owner() {
    // No config_files label -> can't attribute to a package -> not flagged.
    var d = dockerWith(
        List.of(container("rogue", null)),
        Map.of("rogue", new String[] { "DB=core-db" }));
    assertThat(new CoreDbIsolationRule(d).evaluate()).isEmpty();
  }

  @Test
  void catches_core_cache_too() {
    var d = dockerWith(
        List.of(container("app", "dev")),
        Map.of("app", new String[] { "REDIS_URL=redis://core-cache:6379" }));
    List<SecurityFinding> findings = new CoreDbIsolationRule(d).evaluate();
    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).description()).contains("core-cache");
  }

  @Test
  void degrades_to_empty_when_docker_is_down() {
    DockerService d = Mockito.mock(DockerService.class);
    Mockito.when(d.listProjectContainers()).thenThrow(new RuntimeException("socket down"));
    assertThat(new CoreDbIsolationRule(d).evaluate()).isEmpty();
  }
}
