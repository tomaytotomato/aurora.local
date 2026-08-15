package com.tomaytotomato.aurora.identity;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase D iter-5 — invariants over
 * {@code packages/identity/authelia/configuration.yml}.
 *
 * <p>The config file drives session cookie scoping + access-control
 * policy for the whole SSO surface. A silent edit that flips the apex
 * from {@code bypass} to {@code one_factor}, or drops the cookie
 * domain, would either lock everyone out of Aurora or silo the session
 * per-vhost so SSO stops federating. This test pins the shape at
 * enforcement time (SnakeYAML parse) rather than at review time.
 *
 * <p>Reads from a classpath test-resource copy of the config file
 * ({@code src/test/resources/identity/configuration.yml}). See that
 * directory's README for the sync-with-source rule; a separate
 * {@link #snapshot_matches_source} test catches drift when the source
 * file is visible (local dev; not under the sandboxed maven container).
 */
class AutheliaConfigurationInvariantsTests {

  private static final String CLASSPATH_YML = "/identity/configuration.yml";
  private static final Path SOURCE_FILE = Path.of(
      "../../../packages/identity/authelia/configuration.yml"
  );

  @SuppressWarnings("unchecked")
  private static Map<String, Object> load() throws IOException {
    try (var in = AutheliaConfigurationInvariantsTests.class.getResourceAsStream(CLASSPATH_YML)) {
      if (in == null) throw new IOException("missing test resource " + CLASSPATH_YML);
      return (Map<String, Object>) new Yaml().load(in);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void access_control_default_policy_denies() throws IOException {
    Map<String, Object> ac = (Map<String, Object>) load().get("access_control");
    // Deny-by-default is the safe posture: an unmapped subdomain
    // (someone forgets a manifest edit) fails closed rather than
    // silently letting a service run without SSO in front.
    assertThat(ac.get("default_policy")).isEqualTo("deny");
  }

  @Test
  @SuppressWarnings("unchecked")
  void auth_portal_and_apex_are_bypass_so_login_flows_dont_deadlock() throws IOException {
    Map<String, Object> ac = (Map<String, Object>) load().get("access_control");
    List<Map<String, Object>> rules = (List<Map<String, Object>>) ac.get("rules");

    // Authelia's own portal must be reachable pre-auth, otherwise
    // there's no way to reach the sign-in form.
    assertThat(rules).anyMatch(r ->
        "bypass".equals(r.get("policy"))
        && String.valueOf(r.get("domain")).contains("auth."));

    // Aurora dashboard at the apex domain runs its own username +
    // password flow with server-side sessions. If Authelia tried to
    // gate it too the two systems would fight for the request; the
    // pattern here is bypass at Authelia, gate at Aurora.
    assertThat(rules).anyMatch(r ->
        "bypass".equals(r.get("policy"))
        && String.valueOf(r.get("domain")).matches(".*\\{\\{ env \"DOMAIN\" \\}\\}.*")
        && !String.valueOf(r.get("domain")).contains("*")
        && !String.valueOf(r.get("domain")).contains("auth."));
  }

  @Test
  @SuppressWarnings("unchecked")
  void every_subdomain_falls_back_to_two_factor_by_default() throws IOException {
    Map<String, Object> ac = (Map<String, Object>) load().get("access_control");
    List<Map<String, Object>> rules = (List<Map<String, Object>>) ac.get("rules");
    // Non-mapped subdomain → 2FA. Per-package overrides come from
    // the Caddy snippet renderer (D6) and layer on top of this
    // baseline.
    assertThat(rules).anyMatch(r ->
        "two_factor".equals(r.get("policy"))
        && String.valueOf(r.get("domain")).startsWith("*."));
  }

  @Test
  @SuppressWarnings("unchecked")
  void session_cookie_scope_covers_every_subdomain() throws IOException {
    Map<String, Object> session = (Map<String, Object>) load().get("session");
    List<Map<String, Object>> cookies = (List<Map<String, Object>>) session.get("cookies");
    assertThat(cookies).hasSize(1);
    Map<String, Object> cookie = cookies.get(0);
    // Cookie must be scoped to the apex {{ DOMAIN }} so RFC 6265
    // sends it to every subdomain. Any other value (e.g. a specific
    // hostname) would silo the session per-vhost and break SSO
    // federation — the whole point of the phase.
    assertThat(String.valueOf(cookie.get("domain"))).contains("{{ env \"DOMAIN\" }}");
    assertThat(String.valueOf(cookie.get("authelia_url"))).contains("auth.");
  }

  @Test
  @SuppressWarnings("unchecked")
  void authentication_backend_watches_the_users_database_file() throws IOException {
    Map<String, Object> auth = (Map<String, Object>) load().get("authentication_backend");
    Map<String, Object> file = (Map<String, Object>) auth.get("file");
    // AutheliaService writes atomically via rename; watch: true makes
    // Authelia pick up the new file within milliseconds. Losing this
    // flag means user changes in Aurora only propagate on Authelia
    // container restart. Belt-and-braces test guards the wiring.
    assertThat(file.get("watch")).isEqualTo(true);
    assertThat(String.valueOf(file.get("path"))).endsWith("users_database.yml");
  }

  @Test
  @SuppressWarnings("unchecked")
  void hash_algorithm_matches_aurora_side() throws IOException {
    Map<String, Object> auth = (Map<String, Object>) load().get("authentication_backend");
    Map<String, Object> file = (Map<String, Object>) auth.get("file");
    Map<String, Object> pw = (Map<String, Object>) file.get("password");
    // Aurora's AuthService hashes with BCrypt cost 12 (see the header
    // comment there — argon2-jvm SIGSEGVs under musl, migration to
    // a pure-Java argon2 pivot is queued). Authelia MUST match so
    // projected hashes verify on the Authelia side without a rehash-
    // on-first-login dance. Drift here would silently break every
    // login after D2 lands users_database.yml on the live box.
    assertThat(pw.get("algorithm")).isEqualTo("bcrypt");
    Map<String, Object> bcrypt = (Map<String, Object>) pw.get("bcrypt");
    assertThat(bcrypt.get("cost")).isEqualTo(12);
  }

  @Test
  @SuppressWarnings("unchecked")
  void identity_validation_block_present_for_reset_tokens() throws IOException {
    // Authelia 4.38+ refuses to boot without identity_validation for
    // reset flows. Keep it explicit so an image upgrade doesn't
    // silently start denying password-reset requests.
    Map<String, Object> iv = (Map<String, Object>) load().get("identity_validation");
    Map<String, Object> reset = (Map<String, Object>) iv.get("reset_password");
    assertThat(String.valueOf(reset.get("jwt_secret"))).contains("AUTHELIA_JWT_SECRET");
  }

  @Test
  @SuppressWarnings("unchecked")
  void secrets_never_appear_literal_in_the_config() throws IOException {
    try (var in = AutheliaConfigurationInvariantsTests.class.getResourceAsStream(CLASSPATH_YML)) {
      String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      // Only {{ env "..." }} references are allowed for the three
      // managed secrets. If someone accidentally pastes a real hex
      // value here, this test screams — the config is bind-mounted
      // read-only into Authelia AND checked into git, so a literal
      // secret would end up in a repo's public history.
      for (String key : List.of(
          "AUTHELIA_JWT_SECRET",
          "AUTHELIA_SESSION_SECRET",
          "AUTHELIA_STORAGE_ENCRYPTION_KEY")) {
        // Any occurrence must be inside a Go-template env reference.
        String[] lines = body.split("\\R");
        for (String line : lines) {
          if (line.contains(key) && !line.trim().startsWith("#")) {
            assertThat(line)
                .as("secret %s should only appear inside {{ env %s }}, line: %s", key, key, line)
                .containsPattern("\\{\\{ env \"" + key + "\" \\}\\}");
          }
        }
      }
    }
  }

  @Test
  void snapshot_matches_source() throws IOException {
    // Runs only when the sibling packages/identity/ tree is visible
    // (local `mvn test` outside the docker sandbox). The verify
    // script's container only mounts packages/dashboard/backend, so
    // this drift check silently passes there — acceptable, because
    // the invariants above still enforce the shape.
    if (!Files.exists(SOURCE_FILE)) return;
    String source = Files.readString(SOURCE_FILE, StandardCharsets.UTF_8);
    try (var in = AutheliaConfigurationInvariantsTests.class.getResourceAsStream(CLASSPATH_YML)) {
      String snapshot = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      assertThat(snapshot)
          .as("src/test/resources/identity/configuration.yml is out of sync with the source file. Copy the file from packages/identity/authelia/configuration.yml.")
          .isEqualTo(source);
    }
  }
}
