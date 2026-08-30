package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.domain.AdminUser;
import com.tomaytotomato.aurora.domain.RepoState;
import com.tomaytotomato.aurora.domain.Role;
import com.tomaytotomato.aurora.persistence.AdminUserRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AdGuard has to be configured before its first start, or the DNS story the
 * wizard sold does not exist: an unprovisioned AdGuard boots into its own
 * setup wizard with no DNS listener, so {@code *.aurora.local} resolves
 * nowhere and pointing a router at the box takes the house offline.
 */
class AdguardProvisionServiceTests {

  @TempDir
  Path repo;

  private AdminUserRepo users;
  private SystemService system;
  private StateFileService stateFiles;
  private AdguardProvisionService svc;

  @BeforeEach
  void setUp() {
    users = mock(AdminUserRepo.class);
    system = mock(SystemService.class);
    stateFiles = mock(StateFileService.class);
    var props = new AuroraProperties(repo.toString(), "/proc", null, null);
    svc = new AdguardProvisionService(props, users, system, stateFiles);

    when(users.findAll()).thenReturn(List.of(new AdminUser(
        1L, "sarah", "$2a$12$abcdefghijklmnopqrstuv", "UTC", "2026-08-28T00:00:00Z", Role.ADMIN)));
    when(system.lanIp()).thenReturn("192.168.0.110");
    when(stateFiles.readState()).thenReturn(state("aurora.local"));
  }

  private static RepoState state(String domain) {
    return new RepoState(1, "aurora", domain, "2026-08-28T00:00:00Z",
        List.of("core", "privacy"), List.of("cpu"));
  }

  private Path conf() {
    return repo.resolve(AdguardProvisionService.CONF_RELATIVE);
  }

  @Test
  void writesAConfigThatAnswersDnsForTheBoxsDomain() throws Exception {
    assertThat(svc.provisionIfAbsent()).isTrue();

    String yaml = Files.readString(conf());
    // The two things a fresh box actually needs: a DNS listener...
    assertThat(yaml).contains("port: 53");
    assertThat(yaml).contains("bind_hosts:");
    // ...and both the apex and the wildcard pointing back here.
    assertThat(yaml).contains("- domain: aurora.local");
    assertThat(yaml).contains("- domain: '*.aurora.local'");
    assertThat(yaml).contains("answer: 192.168.0.110");
  }

  @Test
  void theAdminIsTheAuroraAdmin_withTheSamePassword() throws Exception {
    svc.provisionIfAbsent();

    String yaml = Files.readString(conf());
    assertThat(yaml).contains("- name: sarah");
    // The bcrypt hash is copied, not re-invented: one password for the box.
    assertThat(yaml).contains("password: $2a$12$abcdefghijklmnopqrstuv");
  }

  @Test
  void neverOverwritesAnExistingConfig() throws Exception {
    Files.createDirectories(conf().getParent());
    Files.writeString(conf(), "# the owner's own blocklists live here\n");

    assertThat(svc.provisionIfAbsent()).isFalse();
    assertThat(Files.readString(conf())).isEqualTo("# the owner's own blocklists live here\n");
  }

  @Test
  void defersWhenThereIsNoAdminYet() {
    when(users.findAll()).thenReturn(List.of());

    assertThat(svc.provisionIfAbsent()).isFalse();
    assertThat(Files.exists(conf())).isFalse();
  }

  @Test
  void defersWhenTheLanAddressIsUnknown() {
    when(system.lanIp()).thenReturn("");

    // Better AdGuard's own wizard than rewrites pointing at nothing.
    assertThat(svc.provisionIfAbsent()).isFalse();
    assertThat(Files.exists(conf())).isFalse();
  }

  @Test
  void followsTheBoxsDomainWhenItIsNotTheDefault() throws Exception {
    when(stateFiles.readState()).thenReturn(state("hearth.local"));

    svc.provisionIfAbsent();

    assertThat(Files.readString(conf())).contains("- domain: '*.hearth.local'");
  }
}
