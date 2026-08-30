package com.tomaytotomato.aurora.services;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link StalwartMailClient} request-building + response-parsing, exercised
 * against canned JMAP responses through the overridable {@link
 * StalwartMailClient#jmapPost} seam. The exact JMAP object shapes here
 * (credentials as a map, primaryKeyViolation on duplicate, the
 * methodResponses envelope) were verified against a live Stalwart v0.16.19
 * before this was written.
 */
class StalwartMailClientTests {

  private static StalwartAdminService admin() {
    StalwartAdminService a = Mockito.mock(StalwartAdminService.class);
    Mockito.when(a.currentCredential()).thenReturn(
        new StalwartAdminService.AdminCredential("admin", "secret", StalwartAdminService.Source.ENV));
    return a;
  }

  /** A client whose HTTP seam returns a fixed body and captures the request. */
  private static StalwartMailClient clientReturning(String response, AtomicReference<String> captured) {
    return new StalwartMailClient(admin(), "http://stalwart:8080/jmap/") {
      @Override
      protected String jmapPost(String requestBody) {
        captured.set(requestBody);
        return response;
      }
    };
  }

  @Test
  void ensureDomain_returns_true_when_created() {
    var captured = new AtomicReference<String>();
    var c = clientReturning(
        "{\"methodResponses\":[[\"x:Domain/set\",{\"created\":{\"d1\":{\"id\":\"b\"}}},\"c1\"]]}",
        captured);
    assertThat(c.ensureDomain("aurora.local")).isTrue();
    // Request carries the stalwart capability + the create shape.
    assertThat(captured.get()).contains("urn:stalwart:jmap");
    assertThat(captured.get()).contains("x:Domain/set");
    assertThat(captured.get()).contains("\"name\":\"aurora.local\"");
  }

  @Test
  void ensureDomain_reports_the_domain_exists_even_when_it_did_not_create_it() {
    // It used to return false here, meaning "I did not create it" — which
    // every caller read as "not ready". MailAccountReconciler believed that
    // and provisioned nothing on any box whose domain already existed,
    // i.e. all of them after the first minute. The question this method
    // answers is now "does the domain exist", which is what callers want.
    var c = clientReturning(
        "{\"methodResponses\":[[\"x:Domain/set\",{\"notCreated\":{\"d1\":"
            + "{\"type\":\"primaryKeyViolation\",\"objectId\":{\"id\":\"b\"}}}},\"c1\"]]}",
        new AtomicReference<>());
    assertThat(c.ensureDomain("aurora.local")).isTrue();
  }

  @Test
  void ensureDomain_throws_on_an_unexpected_failure() {
    var c = clientReturning(
        "{\"methodResponses\":[[\"x:Domain/set\",{\"notCreated\":{\"d1\":"
            + "{\"type\":\"forbidden\"}}},\"c1\"]]}",
        new AtomicReference<>());
    assertThatThrownBy(() -> c.ensureDomain("aurora.local"))
        .isInstanceOf(StalwartMailClient.StalwartApiException.class);
  }

  @Test
  void createMailbox_sends_the_password_as_a_credentials_map_and_returns_the_id() {
    var captured = new AtomicReference<String>();
    // domainIdFor -> Domain/get list; createMailbox -> Account/set created.
    // The client makes two POSTs; return the right body for each by peeking
    // at the request method.
    var c = new StalwartMailClient(admin(), "http://stalwart:8080/jmap/") {
      @Override
      protected String jmapPost(String requestBody) {
        captured.set(requestBody);
        if (requestBody.contains("x:Domain/get")) {
          return "{\"methodResponses\":[[\"x:Domain/get\",{\"list\":"
              + "[{\"id\":\"b\",\"name\":\"aurora.local\"}]},\"c1\"]]}";
        }
        return "{\"methodResponses\":[[\"x:Account/set\",{\"created\":{\"a1\":{\"id\":\"c\"}}},\"c1\"]]}";
      }
    };
    String id = c.createMailbox("bruce", "aurora.local", "correct-horse-battery-staple");
    assertThat(id).isEqualTo("c");
    // The account-create request uses the map shape, not an array.
    assertThat(captured.get()).contains("\"credentials\":{\"0\":{\"@type\":\"Password\"");
    assertThat(captured.get()).contains("\"domainId\":\"b\"");
    assertThat(captured.get()).contains("\"name\":\"bruce\"");
  }

  @Test
  void createMailbox_fails_when_the_domain_is_missing() {
    var c = clientReturning(
        "{\"methodResponses\":[[\"x:Domain/get\",{\"list\":[]},\"c1\"]]}",
        new AtomicReference<>());
    assertThatThrownBy(() -> c.createMailbox("bruce", "aurora.local", "pw"))
        .isInstanceOf(StalwartMailClient.StalwartApiException.class)
        .hasMessageContaining("does not exist");
  }

  @Test
  void createMailbox_surfaces_a_weak_password_rejection() {
    var c = new StalwartMailClient(admin(), "http://stalwart:8080/jmap/") {
      @Override
      protected String jmapPost(String requestBody) {
        if (requestBody.contains("x:Domain/get")) {
          return "{\"methodResponses\":[[\"x:Domain/get\",{\"list\":"
              + "[{\"id\":\"b\",\"name\":\"aurora.local\"}]},\"c1\"]]}";
        }
        return "{\"methodResponses\":[[\"x:Account/set\",{\"notCreated\":{\"a1\":"
            + "{\"type\":\"invalidProperties\",\"description\":\"Password is too weak\"}}},\"c1\"]]}";
      }
    };
    assertThatThrownBy(() -> c.createMailbox("bruce", "aurora.local", "weak"))
        .isInstanceOf(StalwartMailClient.StalwartApiException.class);
  }

  @Test
  void domainIdFor_matches_case_insensitively() {
    var c = clientReturning(
        "{\"methodResponses\":[[\"x:Domain/get\",{\"list\":"
            + "[{\"id\":\"b\",\"name\":\"Aurora.Local\"}]},\"c1\"]]}",
        new AtomicReference<>());
    assertThat(c.domainIdFor("aurora.local")).isEqualTo("b");
  }

  @Test
  void reachable_is_false_when_the_seam_throws() {
    var c = new StalwartMailClient(admin(), "http://stalwart:8080/jmap/") {
      @Override
      protected String jmapPost(String requestBody) {
        throw new StalwartApiException("connection refused");
      }
    };
    assertThat(c.reachable()).isFalse();
  }

  @Test
  void listDomainIds_reads_the_query_ids() {
    var c = clientReturning(
        "{\"methodResponses\":[[\"x:Domain/query\",{\"ids\":[\"b\",\"e\"]},\"c1\"]]}",
        new AtomicReference<>());
    assertThat(c.listDomainIds()).isEqualTo(List.of("b", "e"));
  }

  @Test
  void listMailboxes_parses_address_used_and_created_newest_first() {
    var c = clientReturning(
        "{\"methodResponses\":[[\"x:Account/get\",{\"list\":["
            + "{\"id\":\"h\",\"emailAddress\":\"terry@aurora.local\","
            + "\"usedDiskQuota\":1024,\"quotas\":{},\"createdAt\":\"2026-08-01T00:00:00Z\"},"
            + "{\"id\":\"i\",\"emailAddress\":\"sam@aurora.local\","
            + "\"usedDiskQuota\":0,\"quotas\":{\"maxDiskQuota\":1073741824},"
            + "\"createdAt\":\"2026-08-28T00:00:00Z\"}"
            + "]},\"c1\"]]}",
        new AtomicReference<>());
    var boxes = c.listMailboxes();
    assertThat(boxes).hasSize(2);
    // Newest first: sam (28 Aug) before terry (1 Aug).
    assertThat(boxes.get(0).address()).isEqualTo("sam@aurora.local");
    assertThat(boxes.get(0).quotaBytes()).isEqualTo(1073741824L);
    assertThat(boxes.get(0).usedBytes()).isEqualTo(0L);
    assertThat(boxes.get(1).address()).isEqualTo("terry@aurora.local");
    // Empty quotas map -> null (uncapped), so the UI hides the column.
    assertThat(boxes.get(1).quotaBytes()).isNull();
  }

  @Test
  void listMailboxes_is_empty_when_there_are_none() {
    var c = clientReturning(
        "{\"methodResponses\":[[\"x:Account/get\",{\"list\":[]},\"c1\"]]}",
        new AtomicReference<>());
    assertThat(c.listMailboxes()).isEmpty();
  }

  @Test
  void resetMailboxPassword_sends_the_credentials_map_update() {
    var captured = new AtomicReference<String>();
    var c = clientReturning(
        "{\"methodResponses\":[[\"x:Account/set\",{\"updated\":{\"h\":null}},\"c1\"]]}",
        captured);
    c.resetMailboxPassword("h", "brand-new-strong-password");
    assertThat(captured.get()).contains("\"update\":{\"h\":{\"credentials\":{\"0\":{\"@type\":\"Password\"");
  }

  @Test
  void resetMailboxPassword_throws_when_not_updated() {
    var c = clientReturning(
        "{\"methodResponses\":[[\"x:Account/set\",{\"notUpdated\":{\"h\":{\"type\":\"invalidProperties\"}}},\"c1\"]]}",
        new AtomicReference<>());
    assertThatThrownBy(() -> c.resetMailboxPassword("h", "weak"))
        .isInstanceOf(StalwartMailClient.StalwartApiException.class);
  }

  @Test
  void deleteMailbox_confirms_destroyed() {
    var captured = new AtomicReference<String>();
    var c = clientReturning(
        "{\"methodResponses\":[[\"x:Account/set\",{\"destroyed\":[\"h\"]},\"c1\"]]}",
        captured);
    c.deleteMailbox("h"); // no throw = success
    assertThat(captured.get()).contains("\"destroy\":[\"h\"]");
  }

  @Test
  void deleteMailbox_throws_when_not_destroyed() {
    var c = clientReturning(
        "{\"methodResponses\":[[\"x:Account/set\",{\"notDestroyed\":{\"h\":{\"type\":\"notFound\"}}},\"c1\"]]}",
        new AtomicReference<>());
    assertThatThrownBy(() -> c.deleteMailbox("h"))
        .isInstanceOf(StalwartMailClient.StalwartApiException.class);
  }

  // ─── registry seed (C27) ──────────────────────────────────────

  /**
   * A client that dispatches per JMAP method name: the caller registers
   * a script from {@code method → response body} and the last request
   * body is captured for assertion. Real Stalwart is one endpoint;
   * the seam is dumb, so tests need to be smart.
   */
  private static StalwartMailClient scripted(java.util.Map<String, String> byMethod,
                                             java.util.List<String> capturedBodies) {
    return new StalwartMailClient(admin(), "http://stalwart:8080/jmap/") {
      @Override
      protected String jmapPost(String requestBody) {
        capturedBodies.add(requestBody);
        for (var e : byMethod.entrySet()) {
          if (requestBody.contains(e.getKey())) return e.getValue();
        }
        throw new AssertionError("no scripted response for: " + requestBody);
      }
    };
  }

  @Test
  void ensureSystemSettings_updates_when_the_current_singleton_disagrees() {
    var bodies = new java.util.ArrayList<String>();
    var c = scripted(java.util.Map.of(
        "x:Domain/get", "{\"methodResponses\":[[\"x:Domain/get\","
            + "{\"list\":[{\"id\":\"b\",\"name\":\"aurora.local\"}]},\"c1\"]]}",
        "x:SystemSettings/get", "{\"methodResponses\":[[\"x:SystemSettings/get\","
            + "{\"list\":[{\"id\":\"singleton\","
            + "\"defaultHostname\":\"mail.old.example\",\"defaultDomainId\":\"other\"}]},\"c1\"]]}",
        "x:SystemSettings/set", "{\"methodResponses\":[[\"x:SystemSettings/set\","
            + "{\"updated\":{\"singleton\":null}},\"c1\"]]}"
    ), bodies);

    assertThat(c.ensureSystemSettings("mail.aurora.local", "aurora.local")).isTrue();

    // The Set call carries the hostname literal AND references the
    // domain by its id, not by its name (defaultDomainId is Id<Domain>).
    String setBody = bodies.stream()
        .filter(b -> b.contains("x:SystemSettings/set")).findFirst().orElseThrow();
    assertThat(setBody).contains("\"defaultHostname\":\"mail.aurora.local\"");
    assertThat(setBody).contains("\"defaultDomainId\":\"b\"");
    assertThat(setBody).contains("\"update\":{\"singleton\":");
  }

  @Test
  void ensureSystemSettings_is_a_no_op_when_already_correct() {
    // Idempotency is what makes the scheduled reconcile safe. A boot
    // where hostname + domain are already right must not emit a Set,
    // must not throw, and must return false so the caller does not log
    // a transition line.
    var bodies = new java.util.ArrayList<String>();
    var c = scripted(java.util.Map.of(
        "x:Domain/get", "{\"methodResponses\":[[\"x:Domain/get\","
            + "{\"list\":[{\"id\":\"b\",\"name\":\"aurora.local\"}]},\"c1\"]]}",
        "x:SystemSettings/get", "{\"methodResponses\":[[\"x:SystemSettings/get\","
            + "{\"list\":[{\"id\":\"singleton\","
            + "\"defaultHostname\":\"mail.aurora.local\",\"defaultDomainId\":\"b\"}]},\"c1\"]]}"
    ), bodies);

    assertThat(c.ensureSystemSettings("mail.aurora.local", "aurora.local")).isFalse();
    // Never touched Set.
    assertThat(bodies).noneMatch(b -> b.contains("x:SystemSettings/set"));
  }

  @Test
  void ensureSystemSettings_refuses_when_the_domain_does_not_exist_yet() {
    // The caller is telling us to point defaultDomainId at a domain that
    // does not exist. Fail explicitly instead of writing an invalid id
    // that the server would reject later with a less useful message.
    var c = scripted(java.util.Map.of(
        "x:Domain/get",
        "{\"methodResponses\":[[\"x:Domain/get\",{\"list\":[]},\"c1\"]]}"
    ), new java.util.ArrayList<>());

    assertThatThrownBy(() -> c.ensureSystemSettings("mail.aurora.local", "aurora.local"))
        .isInstanceOf(StalwartMailClient.StalwartApiException.class)
        .hasMessageContaining("does not exist");
  }

  @Test
  void ensureNetworkListener_creates_a_new_listener_when_the_name_is_absent() {
    var bodies = new java.util.ArrayList<String>();
    var c = scripted(java.util.Map.of(
        "x:NetworkListener/get",
        "{\"methodResponses\":[[\"x:NetworkListener/get\",{\"list\":[]},\"c1\"]]}",
        "x:NetworkListener/set",
        "{\"methodResponses\":[[\"x:NetworkListener/set\",{\"created\":{\"n1\":{\"id\":\"L1\"}}},\"c1\"]]}"
    ), bodies);

    assertThat(c.ensureNetworkListener("smtp", "smtp", "[::]:25", false)).isTrue();

    String setBody = bodies.stream()
        .filter(b -> b.contains("x:NetworkListener/set")).findFirst().orElseThrow();
    // bind is a JMAP Set encoded as a numeric-keyed map, not a JSON array.
    // Getting this wrong is exactly what breaks a listener on the server.
    assertThat(setBody).contains("\"bind\":{\"[::]:25\":true}");
    assertThat(setBody).contains("\"name\":\"smtp\"");
    assertThat(setBody).contains("\"protocol\":\"smtp\"");
    assertThat(setBody).contains("\"tlsImplicit\":false");
  }

  @Test
  void ensureNetworkListener_is_a_no_op_when_bind_protocol_and_tls_all_match() {
    var bodies = new java.util.ArrayList<String>();
    var c = scripted(java.util.Map.of(
        "x:NetworkListener/get",
        "{\"methodResponses\":[[\"x:NetworkListener/get\",{\"list\":["
            + "{\"id\":\"L9\",\"name\":\"imaps\","
            + "\"bind\":{\"[::]:993\":true},"
            + "\"protocol\":\"imap\",\"tlsImplicit\":true}]},\"c1\"]]}"
    ), bodies);

    assertThat(c.ensureNetworkListener("imaps", "imap", "[::]:993", true)).isFalse();
    assertThat(bodies).noneMatch(b -> b.contains("x:NetworkListener/set"));
  }

  @Test
  void ensureNetworkListener_updates_the_existing_object_when_a_field_drifted() {
    // Aurora keys off the listener's name. A change to the port in
    // packages/core/compose.yml has to update the SAME listener, not add
    // a second one — duplicate listeners on the same protocol are exactly
    // how mail servers end up with unpredictable delivery.
    var bodies = new java.util.ArrayList<String>();
    var c = scripted(java.util.Map.of(
        "x:NetworkListener/get",
        "{\"methodResponses\":[[\"x:NetworkListener/get\",{\"list\":["
            + "{\"id\":\"L9\",\"name\":\"submission\","
            + "\"bind\":{\"[::]:588\":true},"
            + "\"protocol\":\"smtp\",\"tlsImplicit\":false}]},\"c1\"]]}",
        "x:NetworkListener/set",
        "{\"methodResponses\":[[\"x:NetworkListener/set\",{\"updated\":{\"L9\":null}},\"c1\"]]}"
    ), bodies);

    assertThat(c.ensureNetworkListener("submission", "smtp", "[::]:587", false)).isTrue();

    String setBody = bodies.stream()
        .filter(b -> b.contains("x:NetworkListener/set")).findFirst().orElseThrow();
    // Update by ID, not by name.
    assertThat(setBody).contains("\"update\":{\"L9\":");
    assertThat(setBody).contains("\"bind\":{\"[::]:587\":true}");
  }

  @Test
  void ensureConsoleTracer_creates_one_when_the_list_is_empty() {
    var bodies = new java.util.ArrayList<String>();
    var c = scripted(java.util.Map.of(
        "x:Tracer/get",
        "{\"methodResponses\":[[\"x:Tracer/get\",{\"list\":[]},\"c1\"]]}",
        "x:Tracer/set",
        "{\"methodResponses\":[[\"x:Tracer/set\",{\"created\":{\"t1\":{\"id\":\"T1\"}}},\"c1\"]]}"
    ), bodies);

    assertThat(c.ensureConsoleTracer()).isTrue();

    String setBody = bodies.stream()
        .filter(b -> b.contains("x:Tracer/set")).findFirst().orElseThrow();
    assertThat(setBody).contains("\"@type\":\"Console\"");
    assertThat(setBody).contains("\"level\":\"info\"");
  }

  @Test
  void ensureConsoleTracer_is_a_no_op_when_a_console_tracer_already_exists() {
    // The reason this matters: without it, the 30-minute reconcile would
    // add a second console tracer on every tick, filling the registry
    // with duplicates.
    var bodies = new java.util.ArrayList<String>();
    var c = scripted(java.util.Map.of(
        "x:Tracer/get",
        "{\"methodResponses\":[[\"x:Tracer/get\",{\"list\":["
            + "{\"id\":\"T1\",\"@type\":\"Console\",\"level\":\"info\"}]},\"c1\"]]}"
    ), bodies);

    assertThat(c.ensureConsoleTracer()).isFalse();
    assertThat(bodies).noneMatch(b -> b.contains("x:Tracer/set"));
  }
}
