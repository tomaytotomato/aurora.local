package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.domain.NetworkStorageDevice;
import com.tomaytotomato.aurora.support.FakeCommandRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The discovery service around the parser: what it runs, and what it does
 * when the network, the daemon, or the device is not playing along.
 */
class NetworkStorageServiceTests {

  @Test
  void listensRatherThanScanningTheSubnet() {
    var runner = new FakeCommandRunner();
    new NetworkStorageService(runner).discover();

    String ran = runner.invocations().stream()
        .map(FakeCommandRunner.Invocation::command)
        .reduce("", (a, b) -> a + " " + b);

    // An mDNS browse of announcements this box already receives. Nothing
    // sweeps addresses or ports: a box that quietly probes every host on
    // someone's LAN is not something to install on trust, and it trips
    // intrusion detection on exactly the appliances we are looking for.
    assertThat(ran).contains("avahi-browse");
    assertThat(ran).doesNotContain("nmap");
    // Bounded, so a page load cannot hang on a quiet network.
    assertThat(ran).contains("timeout");
  }

  @Test
  void reportsWhatItFound() {
    var runner = new FakeCommandRunner().stubLines("avahi-browse",
        "=;eth0;IPv4;DiskStation;_smb._tcp;local;ds.local;192.0.2.10;445;\"model=DS220j\"");

    List<NetworkStorageDevice> found = new NetworkStorageService(runner).discover();

    assertThat(found).hasSize(1);
    assertThat(found.get(0).name()).isEqualTo("DiskStation");
    assertThat(found.get(0).model()).isEqualTo("DS220j");
    assertThat(found.get(0).address()).isEqualTo("192.0.2.10");
  }

  @Test
  void advertisingIsNotAnswering() {
    // 192.0.2.0/24 is TEST-NET-1: guaranteed unroutable, so the probe fails
    // the way a sleeping NAS does. The device is still reported — it is
    // genuinely there — but not claimed to be reachable.
    var runner = new FakeCommandRunner().stubLines("avahi-browse",
        "=;eth0;IPv4;asleep;_smb._tcp;local;asleep.local;192.0.2.11;445;");

    List<NetworkStorageDevice> found = new NetworkStorageService(runner).discover();

    assertThat(found).hasSize(1);
    assertThat(found.get(0).reachable()).isFalse();
  }

  @Test
  void aNetworkWithNothingOnItIsAnEmptyList_notAnError() {
    assertThat(new NetworkStorageService(new FakeCommandRunner()).discover()).isEmpty();
  }

  @Test
  void noAvahiOrNoDbusIsAlsoJustEmpty() {
    // Aurora may run somewhere with no working mDNS stack. That is "found
    // nothing", which renders as an honest empty state; it is not an error
    // worth putting in front of anyone.
    CommandRunner exploding = new CommandRunner() {
      @Override
      public Result run(Path workingDir, Duration timeout, Map<String, String> env,
                        List<String> argv) {
        throw new UnsupportedOperationException("not used");
      }

      @Override
      public int stream(Path workingDir, Map<String, String> env, List<String> argv,
                        Consumer<String> onLine, CancelToken cancelToken) {
        throw new RuntimeException("avahi-browse: command not found");
      }
    };

    assertThat(new NetworkStorageService(exploding).discover()).isEmpty();
  }

  @Test
  void aFailurePartWayThroughDoesNotHalfReport() {
    CommandRunner diesMidway = new CommandRunner() {
      @Override
      public Result run(Path workingDir, Duration timeout, Map<String, String> env,
                        List<String> argv) {
        throw new UnsupportedOperationException("not used");
      }

      @Override
      public int stream(Path workingDir, Map<String, String> env, List<String> argv,
                        Consumer<String> onLine, CancelToken cancelToken) {
        onLine.accept("=;eth0;IPv4;nas;_smb._tcp;local;nas.local;192.0.2.12;445;");
        throw new RuntimeException("daemon went away");
      }
    };

    // A list that silently omits half the devices is worse than one that
    // says it found none: the owner would go looking for the missing box.
    assertThat(new NetworkStorageService(diesMidway).discover()).isEmpty();
  }
}
