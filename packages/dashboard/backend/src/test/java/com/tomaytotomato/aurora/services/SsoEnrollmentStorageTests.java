package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Where the second-factor count comes from.
 *
 * <p>Authelia's storage moved from its own SQLite file to the shared core
 * Postgres instance, but this service kept looking for
 * {@code data/authelia/db.sqlite3}. Because an unreadable database is
 * (correctly) reported as "Authelia is not up yet" rather than "no factors
 * enrolled", the effect on every freshly-installed box was that the wizard's
 * SSO step sat on "Waiting for the SSO service to finish starting" forever,
 * and the enrolment link it exists to hand over never appeared — leaving
 * every {@code two_factor} vhost unopenable without a shell.
 */
class SsoEnrollmentStorageTests {

  @TempDir
  Path repo;

  private DockerService docker;
  private SsoEnrollmentService svc;

  @BeforeEach
  void setUp() {
    docker = mock(DockerService.class);
    var props = new AuroraProperties(repo.toString(), "/proc", null, null);
    svc = new SsoEnrollmentService(props, docker);
    // No notification file anywhere by default.
    when(docker.readFileFromContainer(any(), any())).thenReturn(Optional.empty());
  }

  private void psqlAnswers(String table, String stdout) {
    when(docker.execCapture(eq("core-db"), eq("psql"), eq("-U"), eq("postgres"),
        eq("-d"), eq("authelia"), eq("-tAc"), eq("select count(*) from " + table)))
        .thenReturn(Optional.of(new DockerService.ExecResult(
            stdout.getBytes(StandardCharsets.UTF_8), "", 0L)));
  }

  @Test
  void readsFactorCountsFromTheSharedPostgres() {
    psqlAnswers("webauthn_credentials", "1\n");
    psqlAnswers("totp_configurations", "0\n");

    var status = svc.status();

    assertThat(status.autheliaUp()).isTrue();
    assertThat(status.enrolled()).isTrue();
    assertThat(status.factorCount()).isEqualTo(1);
    assertThat(status.passkeyCount()).isEqualTo(1);
  }

  @Test
  void anEmptySchemaIsUpButNotEnrolled() {
    psqlAnswers("webauthn_credentials", "0\n");
    psqlAnswers("totp_configurations", "0\n");

    var status = svc.status();

    // This is the state a fresh box is genuinely in, and the SSO step needs
    // to render its instructions rather than the "still starting" holding
    // message.
    assertThat(status.autheliaUp()).isTrue();
    assertThat(status.enrolled()).isFalse();
  }

  @Test
  void anUnreachableDatabaseIsReportedAsNotUp_neverAsZeroFactors() {
    // execCapture returns empty: core-db stopped, psql missing, socket gone.
    when(docker.execCapture(any(), any())).thenReturn(Optional.empty());

    var status = svc.status();

    assertThat(status.autheliaUp()).isFalse();
    assertThat(status.enrolled()).isFalse();
  }
}
