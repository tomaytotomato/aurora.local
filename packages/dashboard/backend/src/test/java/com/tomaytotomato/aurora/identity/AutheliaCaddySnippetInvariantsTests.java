package com.tomaytotomato.aurora.identity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase D iter-8 (D7) — invariants over
 * {@code packages/core/caddy.snippet}.
 *
 * <p>This file is loaded by Caddy at startup + on every {@code --watch}
 * fsevent, and every Aurora-managed vhost with {@code sso.protect: true}
 * pulls its {@code (authelia)} named route via {@code import authelia}
 * (D6). A silent edit that drops the {@code Remote-*} header stripping,
 * removes the {@code copy_headers} directive, or forgets to bypass the
 * auth portal itself would either turn SSO into a security theatre
 * (trusting client-supplied Remote-User) or lock Bruce out of the
 * login page.
 *
 * <p>Reads from a classpath test-resource copy of the file. Same
 * sync-with-source pattern documented in D4's
 * {@link AutheliaConfigurationInvariantsTests}.
 */
class AutheliaCaddySnippetInvariantsTests {

  private static final String CLASSPATH_SNIPPET = "/core/caddy.snippet";
  private static final Path SOURCE_FILE = Path.of(
      "../../packages/core/caddy.snippet"
  );

  private static String load() throws IOException {
    try (var in = AutheliaCaddySnippetInvariantsTests.class.getResourceAsStream(CLASSPATH_SNIPPET)) {
      if (in == null) throw new IOException("missing test resource " + CLASSPATH_SNIPPET);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void defines_the_reusable_authelia_snippet() throws IOException {
    // Every protected vhost's CaddySnippetService injection resolves
    // to this name. If a future edit accidentally renames it (say
    // (auth) instead of (authelia)), every SSO-protected route
    // breaks at Caddy reload with 'unknown import: authelia'.
    assertThat(load()).contains("(authelia) {");
  }

  @Test
  void strips_client_supplied_trusted_headers_before_forward_auth() throws IOException {
    // Critical hardening: without this a LAN device could set
    // Remote-User: admin and impersonate the admin — the upstream
    // service (Grafana, Paperless, Forgejo) trusts the header because
    // the request came from Caddy on aurora_net.
    String body = load();
    for (String header : new String[]{"Remote-User", "Remote-Groups", "Remote-Email", "Remote-Name"}) {
      assertThat(body)
          .as("must strip incoming %s before forward_auth", header)
          .contains("request_header -" + header);
    }
  }

  @Test
  void forward_auth_copies_all_four_authelia_response_headers() throws IOException {
    // copy_headers is what makes trusted-header auth work downstream.
    // Every Aurora-managed trusted-header service (Grafana, Paperless,
    // Forgejo) reads at least Remote-User + Remote-Groups from the
    // request; a missing copy = mysterious re-authentication loop.
    String body = load();
    assertThat(body).contains("copy_headers Remote-User Remote-Groups Remote-Email Remote-Name");
  }

  @Test
  void forward_auth_uses_authelia_container_and_correct_endpoint() throws IOException {
    // Container name pinned so a rename in packages/core/compose.yml
    // triggers this test rather than a mysterious 502 from Caddy at
    // the first login attempt. Endpoint pinned so an Authelia version
    // bump that moves the path breaks here first.
    String body = load();
    assertThat(body).contains("forward_auth authelia:9091 {");
    assertThat(body).contains("uri /api/authz/forward-auth");
  }

  @Test
  void sends_x_forwarded_headers_so_authelia_sees_the_original_request() throws IOException {
    // Authelia's access-control rules key off the ORIGINAL host / uri
    // / method, not the forward-auth request URL. Caddy sets these
    // headers by default, but pinning them here means a future Caddy
    // config that scrubs X-Forwarded-* at the apex doesn't silently
    // break Authelia decisions.
    String body = load();
    assertThat(body).contains("header_up X-Forwarded-Method");
    assertThat(body).contains("header_up X-Forwarded-Proto");
    assertThat(body).contains("header_up X-Forwarded-Host");
    assertThat(body).contains("header_up X-Forwarded-Uri");
    assertThat(body).contains("header_up X-Forwarded-For");
  }

  @Test
  void auth_portal_vhost_does_not_import_authelia_recursively() throws IOException {
    // The auth.{$DOMAIN} vhost MUST NOT import authelia — that would
    // send Authelia's own login page through Authelia's forward-auth,
    // and browsers would spin forever on the redirect loop.
    // Assertion: every occurrence of `import authelia` in the file
    // sits inside a block that doesn't declare the auth.{$DOMAIN}
    // vhost. Since this file itself defines the auth vhost and the
    // (authelia) snippet but no other vhost, we can just assert
    // there is no `import authelia` line at all — every `import`
    // lives in per-package snippets emitted by CaddySnippetService.
    String body = load();
    for (String line : body.split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.startsWith("#")) continue;
      assertThat(trimmed)
          .as("packages/core/caddy.snippet must not `import authelia`; line: %s", line)
          .doesNotStartWith("import authelia");
    }
  }

  @Test
  void auth_portal_serves_http_and_https_on_the_auth_subdomain() throws IOException {
    String body = load();
    assertThat(body).contains("http://auth.{$DOMAIN} {");
    assertThat(body).contains("https://auth.{$DOMAIN} {");
    assertThat(body).contains("reverse_proxy authelia:9091");
    // tls internal — Caddy's self-signed CA (see get-caddy-root-cert.sh)
    // signs auth.{DOMAIN} the same way it signs every other vhost. If
    // this line disappears, browsers get a certificate error and the
    // 2FA-enrolment link in the notification.txt won't open.
    assertThat(body).contains("tls internal");
  }

  @Test
  void snapshot_matches_source() throws IOException {
    if (!Files.exists(SOURCE_FILE)) return;
    String source = Files.readString(SOURCE_FILE, StandardCharsets.UTF_8);
    String snapshot = load();
    assertThat(snapshot)
        .as("src/test/resources/core/caddy.snippet is out of sync with the source file. Copy it from packages/core/caddy.snippet.")
        .isEqualTo(source);
  }
}
