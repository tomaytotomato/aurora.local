package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.services.SmbShareService.Result;
import com.tomaytotomato.aurora.services.SmbShareService.Share;
import com.tomaytotomato.aurora.support.FakeCommandRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class SmbShareServiceTests {

  @Test
  void listsTheFoldersAPersonWouldWantToPick() {
    var runner = new FakeCommandRunner().stubLines("smbclient",
        "Disk|Films|Movies and telly",
        "Disk|Photos|",
        "IPC|IPC$|IPC Service",
        "Disk|print$|Printer Drivers",
        "Printer|HP_LaserJet|");

    Result r = new SmbShareService(runner).list("192.0.2.10", null, null);

    assertThat(r.outcome()).isEqualTo(Result.OK);
    // Printers, the IPC control channel and driver shares are not places to
    // keep films; offering them would be actively unhelpful.
    assertThat(r.shares()).containsExactly(
        new Share("Films", "Movies and telly"),
        new Share("Photos", null));
  }

  @Test
  void readsTheHumanTableToo_becauseNotEverySambaBuildHonoursDashG() {
    var runner = new FakeCommandRunner().stubLines("smbclient",
        "",
        "\tSharename       Type      Comment",
        "\t---------       ----      -------",
        "\tFilms           Disk      Movies and telly",
        "\tIPC$            IPC       IPC Service",
        "\tBackups         Disk      ");

    Result r = new SmbShareService(runner).list("192.0.2.10", null, null);

    assertThat(r.shares()).extracting(Share::name).containsExactly("Films", "Backups");
  }

  @Test
  void triesGuestFirstSoNobodyIsAskedForCredentialsTheyMayNotNeed() {
    var runner = new FakeCommandRunner();
    new SmbShareService(runner).list("192.0.2.10", null, null);

    String cmd = runner.invocations().get(0).command();
    // -N is "no password prompt": most NAS boxes allow a guest listing,
    // and asking for a username before we know one is needed is a wall in
    // front of a door that is already open.
    assertThat(cmd).contains("-N");
    assertThat(cmd).doesNotContain("-U");
  }

  @Test
  void keepsThePasswordOutOfArgvWhereEveryProcessCouldReadIt() {
    var runner = new FakeCommandRunner();
    new SmbShareService(runner).list("192.0.2.10", "sarah", "hunter2");

    var call = runner.invocations().get(0);
    assertThat(call.command()).contains("-U").contains("sarah");
    assertThat(call.command()).doesNotContain("hunter2");
    // Passed through the environment instead: argv is visible in `ps` to
    // every user on the host.
    assertThat(call.env()).containsEntry("PASSWD", "hunter2");
  }

  @Test
  void tellsTheOwnerWhichOfTheThingsWentWrong() {
    // "wrong password", "needs a password" and "device is asleep" need
    // different reactions from a person, so they must not collapse.
    CommandRunner denied = failing("session setup failed: NT_STATUS_LOGON_FAILURE");
    assertThat(new SmbShareService(denied).list("192.0.2.10", "sarah", "wrong").outcome())
        .isEqualTo(Result.DENIED);

    // The same refusal, but nobody had typed a username yet. Reported live
    // against a real NAS as "That username and password didn't open it",
    // which is nonsense to read and blames the reader for a mistake they
    // did not make.
    var guest = new SmbShareService(denied).list("192.0.2.10", null, null);
    assertThat(guest.outcome()).isEqualTo(Result.CREDENTIALS_REQUIRED);
    assertThat(guest.detail()).isEqualTo("This device needs a username and password.");

    CommandRunner down = failing("Connection to 192.0.2.10 failed (Error NT_STATUS_IO_TIMEOUT)");
    assertThat(new SmbShareService(down).list("192.0.2.10", null, null).outcome())
        .isEqualTo(Result.UNREACHABLE);
  }

  @Test
  void aDeviceWithNoSharesIsAnEmptyListNotAFailure() {
    var runner = new FakeCommandRunner().stubLines("smbclient", "IPC|IPC$|IPC Service");

    Result r = new SmbShareService(runner).list("192.0.2.10", null, null);

    assertThat(r.outcome()).isEqualTo(Result.OK);
    assertThat(r.shares()).isEmpty();
  }

  /** A runner whose command exits non-zero with the given stderr-ish text. */
  private static CommandRunner failing(String output) {
    return new CommandRunner() {
      @Override
      public Result run(Path workingDir, Duration timeout, Map<String, String> env,
                        List<String> argv) {
        throw new UnsupportedOperationException("not used");
      }

      @Override
      public int stream(Path workingDir, Map<String, String> env, List<String> argv,
                        Consumer<String> onLine, CancelToken cancelToken) {
        onLine.accept(output);
        return 1;
      }
    };
  }
}
