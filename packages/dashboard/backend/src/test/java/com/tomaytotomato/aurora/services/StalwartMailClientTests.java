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
  void ensureDomain_returns_false_when_it_already_exists() {
    var c = clientReturning(
        "{\"methodResponses\":[[\"x:Domain/set\",{\"notCreated\":{\"d1\":"
            + "{\"type\":\"primaryKeyViolation\",\"objectId\":{\"id\":\"b\"}}}},\"c1\"]]}",
        new AtomicReference<>());
    assertThat(c.ensureDomain("aurora.local")).isFalse();
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
}
