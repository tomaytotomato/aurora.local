package com.tomaytotomato.aurora;

import com.tomaytotomato.aurora.cli.ResetAdminPasswordCli;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;

@SpringBootApplication
public class AuroraApplication {

  /**
   * CLI dispatch keyword recognised before the normal Spring Boot app
   * starts — see {@code scripts/reset-admin-password.sh}. Kept as a plain
   * arg check (rather than a Spring {@code ApplicationRunner}) so the
   * break-glass path never depends on the web server, docker.sock, or
   * D-Bus starting up cleanly; it only needs the JVM and the SQLite file.
   */
  private static final String RESET_ADMIN_PASSWORD_CMD = "reset-admin-password";

  public static void main(String[] args) {
    if (args.length > 0 && RESET_ADMIN_PASSWORD_CMD.equals(args[0])) {
      int exitCode = ResetAdminPasswordCli.run(
          Arrays.copyOfRange(args, 1, args.length), System.in, System.out, System.err);
      System.exit(exitCode);
      return;
    }
    SpringApplication.run(AuroraApplication.class, args);
  }
}
