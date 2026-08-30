package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import com.tomaytotomato.aurora.persistence.SettingsRepo;
import com.tomaytotomato.aurora.support.FakeCommandRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NetworkMountServiceTests {

  private Map<String, String> store;
  private SettingsRepo settings;

  @BeforeEach
  void setUp() {
    store = new HashMap<>();
    settings = mock(SettingsRepo.class);
    when(settings.get(anyString())).thenAnswer(i -> Optional.ofNullable(store.get(i.getArgument(0))));
    doAnswer(i -> store.put(i.getArgument(0), i.getArgument(1)))
        .when(settings).put(anyString(), anyString());
  }

  private NetworkMountService service(CommandRunner runner) {
    return new NetworkMountService(runner, settings, mock(AuditEventRepo.class), () -> 1000);
  }

  @Test
  void attachesAsADockerVolumeSoASleepingNasCannotStopTheBoxBooting() {
    var runner = new FakeCommandRunner();
    var result = service(runner).attach("Films", "192.0.2.10", "films", "aurora", "pw", true);

    assertThat(result.ok()).isTrue();
    String created = runner.invocations().stream().map(FakeCommandRunner.Invocation::command)
        .filter(c -> c.contains("volume create")).findFirst().orElse("");

    // A docker volume, mounted when a container starts — not fstab, where a
    // NAS that is off can leave a headless box at a recovery prompt.
    assertThat(created).contains("type=cifs");
    assertThat(created).contains("device=//192.0.2.10/films");
    // Named so `docker volume ls` explains itself.
    assertThat(created).contains("aurora_nas_192_0_2_10_films");
  }

  @Test
  void mountsWithTheUidAppsRunAs_orTheyCannotReadTheirOwnMedia() {
    var runner = new FakeCommandRunner();
    service(runner).attach("Films", "192.0.2.10", "films", "aurora", "pw", false);

    String created = runner.invocations().stream().map(FakeCommandRunner.Invocation::command)
        .filter(c -> c.contains("volume create")).findFirst().orElse("");
    assertThat(created).contains("uid=1000").contains("gid=1000");
    // SMB1 is off by default on every current NAS; negotiating down to it
    // would be a security regression dressed up as compatibility.
    assertThat(created).contains("vers=3.0");
  }

  @Test
  void readOnlyIsHonouredSoACompromisedAppCannotDeleteTheOwnersFilms() {
    var runner = new FakeCommandRunner();
    service(runner).attach("Films", "192.0.2.10", "films", "aurora", "pw", true);

    String created = runner.invocations().stream().map(FakeCommandRunner.Invocation::command)
        .filter(c -> c.contains("volume create")).findFirst().orElse("");
    assertThat(created).contains(",ro");
  }

  @Test
  void provesTheMountWorksBeforeCallingItAttached() {
    // Docker creates a CIFS volume happily whether or not the credentials
    // are right: nothing is mounted until something uses it. Recording
    // success at creation time would tell the owner it worked and leave
    // them to find out days later when an app fails to start.
    var runner = new FakeCommandRunner();
    service(runner).attach("Films", "192.0.2.10", "films", "aurora", "pw", false);

    assertThat(runner.invocations()).extracting(FakeCommandRunner.Invocation::command)
        .anyMatch(c -> c.startsWith("docker run") && c.contains("/probe"));
  }

  @Test
  void aFailedProbeLeavesNoVolumeBehindLookingLikeItWorked() {
    var runner = new RecordingRunner("mount error(13): Permission denied", 1, "docker run");
    var result = service(runner).attach("Films", "192.0.2.10", "films", "aurora", "wrong", false);

    assertThat(result.ok()).isFalse();
    assertThat(result.detail()).isEqualTo("That username and password didn't open the folder.");
    assertThat(runner.commands).anyMatch(c -> c.contains("volume rm"));
    assertThat(service(runner).list()).isEmpty();
  }

  @Test
  void saysWhichMistakeTheOwnerMade() {
    // Docker reports all of these as a mount failure with a kernel errno,
    // which is not something to put in front of anyone.
    assertThat(service(new RecordingRunner("No such file or directory", 1, "docker run"))
        .attach("x", "192.0.2.10", "nope", "u", "p", false).detail())
        .isEqualTo("That folder isn't there. Check the name on the device.");

    assertThat(service(new RecordingRunner("Host is down", 1, "docker run"))
        .attach("x", "192.0.2.10", "films", "u", "p", false).detail())
        .isEqualTo("That device didn't answer. It may be asleep.");
  }

  @Test
  void neverWritesThePasswordIntoAurorasOwnSettings() {
    var runner = new FakeCommandRunner();
    service(runner).attach("Films", "192.0.2.10", "films", "aurora", "hunter2", false);

    // It lives in the docker volume definition. Copying it here would put
    // the same secret in a second place for no gain.
    assertThat(store.get(NetworkMountService.SETTINGS_KEY)).doesNotContain("hunter2");
    assertThat(store.get(NetworkMountService.SETTINGS_KEY)).contains("aurora");
  }

  @Test
  void reattachingTheSameShareReplacesItRatherThanFailing() {
    var runner = new FakeCommandRunner();
    var svc = service(runner);
    svc.attach("Films", "192.0.2.10", "films", "aurora", "old", false);
    svc.attach("Films", "192.0.2.10", "films", "aurora", "new", false);

    assertThat(svc.list()).hasSize(1);
  }

  @Test
  void detachingForgetsItAndRemovesTheVolume() {
    var runner = new FakeCommandRunner();
    var svc = service(runner);
    svc.attach("Films", "192.0.2.10", "films", "aurora", "pw", false);
    String id = svc.list().get(0).id();

    assertThat(svc.detach(id)).isTrue();
    assertThat(svc.list()).isEmpty();
    assertThat(runner.invocations()).extracting(FakeCommandRunner.Invocation::command)
        .anyMatch(c -> c.contains("volume rm"));
  }

  @Test
  void guestSharesNeedNoCredentials() {
    var runner = new FakeCommandRunner();
    service(runner).attach("Public", "192.0.2.10", "public", null, null, false);

    String created = runner.invocations().stream().map(FakeCommandRunner.Invocation::command)
        .filter(c -> c.contains("volume create")).findFirst().orElse("");
    assertThat(created).contains("guest");
    assertThat(created).doesNotContain("username=");
  }

  /** Fails one kind of command with given output; succeeds at everything else. */
  private static final class RecordingRunner implements CommandRunner {
    final List<String> commands = new ArrayList<>();
    private final String output;
    private final int exit;
    private final String failWhenContains;

    RecordingRunner(String output, int exit, String failWhenContains) {
      this.output = output;
      this.exit = exit;
      this.failWhenContains = failWhenContains;
    }

    @Override
    public Result run(Path workingDir, Duration timeout, Map<String, String> env,
                      List<String> argv) {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public int stream(Path workingDir, Map<String, String> env, List<String> argv,
                      Consumer<String> onLine, CancelToken cancelToken) {
      String cmd = String.join(" ", argv);
      commands.add(cmd);
      if (cmd.contains(failWhenContains)) {
        onLine.accept(output);
        return exit;
      }
      return 0;
    }
  }
}
