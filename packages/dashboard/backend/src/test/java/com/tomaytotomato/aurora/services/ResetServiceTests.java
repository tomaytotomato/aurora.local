package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import com.tomaytotomato.aurora.services.ResetService.ResetHelperFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The bit of {@link ResetService} that matters is the {@code docker run}
 * argv: it is what decides whether the helper container can (a) reach the
 * repo, (b) delete root-owned {@code data/} subtrees, and (c) survive
 * aurora's own container being torn down half a step later. Getting any
 * of those wrong wipes the box wrong. So we exercise the seams and pin
 * the shape.
 *
 * <p>What deliberately is not pinned: the exact 6-second sleep, the
 * container-name suffix, and the label value. Those are copy-level
 * decisions the loop is free to change without breaking the contract.
 */
class ResetServiceTests {

  private AuroraProperties props;
  private FakeCommandRunner commands;
  private AuditEventRepo audit;
  private ResetService reset;

  @BeforeEach
  void setUp() {
    props = new AuroraProperties("/home/bruce/aurora.local", "/proc",
        List.of(), new AuroraProperties.Docker("unix:///var/run/docker.sock"));
    commands = new FakeCommandRunner();
    audit = Mockito.mock(AuditEventRepo.class);
    reset = new ResetService(props, commands, audit,
        () -> "ghcr.io/tomaytotomato/aurora:test");
  }

  @Test
  void records_an_audit_event_before_the_helper_runs() {
    // The audit row is the only piece of evidence that survives if the
    // helper does its job — everything else (containers, db, disks) is
    // about to be destroyed. So it has to be written first.
    commands.nextExit = 0;
    commands.nextOutput = List.of("dc0ffee1234");

    reset.start(42L);

    Mockito.verify(audit).record(Mockito.eq(42L), Mockito.eq("reset.start"),
        Mockito.eq("box"), Mockito.contains("\"confirm\":\"RESET\""));
  }

  @Test
  void docker_run_command_uses_docker_run_detached_and_removed() {
    commands.nextExit = 0;
    commands.nextOutput = List.of("dc0ffee1234");

    reset.start(1L);

    List<String> argv = commands.lastArgv;
    assertThat(argv.get(0)).isEqualTo("docker");
    assertThat(argv.get(1)).isEqualTo("run");
    // Detached, because the helper has to outlive the aurora container
    // that is about to remove itself.
    assertThat(argv).contains("-d");
    // --rm so a successful helper does not leave a corpse behind.
    assertThat(argv).contains("--rm");
  }

  @Test
  void helper_runs_as_root_so_it_can_delete_root_owned_data_dirs() {
    // Bind-mounted service data trees end up root-owned; without --user
    // 0:0 the reset script would have to sudo, which the container has
    // no way of doing.
    commands.nextExit = 0;
    commands.nextOutput = List.of("dc0ffee");

    reset.start(1L);

    int idx = commands.lastArgv.indexOf("--user");
    assertThat(idx).isPositive();
    assertThat(commands.lastArgv.get(idx + 1)).isEqualTo("0:0");
  }

  @Test
  void helper_carries_a_role_label_but_not_the_aurora_compose_project_label() {
    // reset.sh matches containers to delete by
    //   com.docker.compose.project=aurora
    // so the helper MUST NOT wear that label — otherwise it deletes
    // itself mid-run.
    commands.nextExit = 0;
    commands.nextOutput = List.of("dc0ffee");

    reset.start(1L);

    // We label the helper so an operator can find it later, but with a
    // key nowhere near com.docker.compose.project.
    int idx = commands.lastArgv.indexOf("--label");
    assertThat(idx).isPositive();
    String value = commands.lastArgv.get(idx + 1);
    assertThat(value).startsWith("aurora.role=");
    assertThat(commands.lastArgv)
        .noneMatch(a -> a.contains("com.docker.compose.project=aurora"));
  }

  @Test
  void repo_is_mounted_at_its_host_path_identity_style() {
    // Same trick aurora.compose.yml uses: mount the repo at its host
    // path so any `docker compose` invocation inside the helper resolves
    // relative bind mounts against a path the host daemon can also see.
    commands.nextExit = 0;
    commands.nextOutput = List.of("dc0ffee");

    reset.start(1L);

    String expected = "/home/bruce/aurora.local:/home/bruce/aurora.local:rw";
    // -v <expected>
    int idx = commands.lastArgv.indexOf(expected);
    assertThat(idx).isPositive();
    assertThat(commands.lastArgv.get(idx - 1)).isEqualTo("-v");
  }

  @Test
  void docker_socket_is_mounted_so_the_helper_can_remove_containers() {
    commands.nextExit = 0;
    commands.nextOutput = List.of("dc0ffee");

    reset.start(1L);

    int idx = commands.lastArgv.indexOf("/var/run/docker.sock:/var/run/docker.sock:rw");
    assertThat(idx).isPositive();
    assertThat(commands.lastArgv.get(idx - 1)).isEqualTo("-v");
  }

  @Test
  void helper_command_runs_reset_sh_with_yes_and_delays_before_it_starts() {
    // The delay is what lets the HTTP response leave the process before
    // the process is killed. --yes is what lets the script run without a
    // TTY prompt. Both are contract, not incidental copy.
    commands.nextExit = 0;
    commands.nextOutput = List.of("dc0ffee");

    reset.start(1L);

    // The last argv element is the -c string bash will execute.
    String script = commands.lastArgv.getLast();
    assertThat(script).contains("sleep");
    assertThat(script).contains("bash scripts/reset.sh --yes");
  }

  @Test
  void when_docker_refuses_to_start_the_helper_we_raise_and_nothing_is_destroyed() {
    // A -1 exit or a non-zero exit from the outer `docker run` means
    // the helper never existed. The service must tell the operator
    // that nothing has been touched, not silently pretend it worked.
    commands.nextExit = 125;
    commands.nextOutput = List.of("Error response from daemon: no such image");

    assertThatThrownBy(() -> reset.start(1L))
        .isInstanceOf(ResetHelperFailedException.class)
        .hasMessageContaining("125");
  }

  @Test
  void a_missing_repo_path_is_a_hard_stop_before_the_audit_row_is_written() {
    // Aurora that does not know where its repo is cannot render the
    // helper's mount argument. Writing an audit row and then failing
    // to launch would suggest a wipe happened when it did not.
    AuroraProperties bad = new AuroraProperties("", "/proc", List.of(),
        new AuroraProperties.Docker("unix:///var/run/docker.sock"));
    ResetService badReset = new ResetService(bad, commands, audit,
        () -> "ghcr.io/tomaytotomato/aurora:test");

    assertThatThrownBy(() -> badReset.start(1L))
        .isInstanceOf(ResetHelperFailedException.class);
    Mockito.verifyNoInteractions(audit);
  }

  // ------------------------------------------------------------------
  // Test double: captures the last argv passed to stream() and returns
  // whatever the test set up. No real process is spawned.
  // ------------------------------------------------------------------
  private static class FakeCommandRunner implements CommandRunner {
    List<String> lastArgv;
    int nextExit = 0;
    List<String> nextOutput = List.of();

    @Override
    public CommandRunner.Result run(java.nio.file.Path workingDir,
                                    java.time.Duration timeout,
                                    Map<String, String> env,
                                    List<String> argv) {
      lastArgv = List.copyOf(argv);
      return CommandRunner.Result.of(nextExit, nextOutput);
    }

    @Override
    public int stream(java.nio.file.Path workingDir,
                      Map<String, String> env,
                      List<String> argv,
                      Consumer<String> onLine,
                      CancelToken cancelToken) {
      lastArgv = List.copyOf(argv);
      for (String line : nextOutput) onLine.accept(line);
      return nextExit;
    }
  }
}
