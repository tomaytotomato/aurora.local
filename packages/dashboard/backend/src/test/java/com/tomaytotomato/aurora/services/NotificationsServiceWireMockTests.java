package com.tomaytotomato.aurora.services;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.tomaytotomato.aurora.persistence.NotificationChannelRepo;
import com.tomaytotomato.aurora.persistence.NotificationDeliveryRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mockito;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * {@link NotificationsService#test(String)} against a real HTTP server
 * (WireMock) rather than a mocked {@code HttpClient} — the behaviour that
 * matters is what happens on the wire (headers, body shape, how a status
 * code or a dead port turns into an honest result), which a mocked
 * client can't exercise.
 *
 * <p>{@link NotificationChannelRepo} and {@link NotificationDeliveryRepo}
 * are still Mockito mocks here (not the concern under test, and pulling
 * in a real JdbcTemplate for a unit test would be redundant with {@code
 * NotificationsControllerIntegrationTest}, which already covers the
 * persistence path end to end).
 */
class NotificationsServiceWireMockTests {

  @RegisterExtension
  static WireMockExtension wm = WireMockExtension.newInstance().build();

  private NotificationChannelRepo channelRepo;
  private NotificationDeliveryRepo deliveryRepo;
  private NotificationsService service;

  @BeforeEach
  void setUp() {
    channelRepo = Mockito.mock(NotificationChannelRepo.class);
    deliveryRepo = Mockito.mock(NotificationDeliveryRepo.class);
    HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(1))
        .build();
    service = new NotificationsService(channelRepo, deliveryRepo, http);
  }

  private NotificationChannelRepo.Row channel(String kind, String path) {
    return new NotificationChannelRepo.Row(
        "chan-1", kind, "Test channel", wm.baseUrl() + path,
        List.of("job-failed"), true, null, null, null);
  }

  @Nested
  @DisplayName("a successful send")
  class Success {

    @Test
    void ntfy_posts_plain_text_with_a_title_header() {
      wm.stubFor(post("/ntfy-topic").willReturn(aResponse().withStatus(200)));
      Mockito.when(channelRepo.findById("chan-1")).thenReturn(java.util.Optional.of(channel("ntfy", "/ntfy-topic")));

      Map<String, Object> result = service.test("chan-1").orElseThrow();

      assertThat(result.get("result")).isEqualTo("ok");
      assertThat(result.get("error")).isNull();
      wm.verify(postRequestedFor(urlEqualTo("/ntfy-topic"))
          .withHeader("Title", equalTo("Aurora"))
          .withRequestBody(equalTo("Test message from Aurora")));
    }

    @Test
    void discord_posts_a_content_field() {
      wm.stubFor(post("/webhooks/1").willReturn(aResponse().withStatus(204)));
      Mockito.when(channelRepo.findById("chan-1")).thenReturn(java.util.Optional.of(channel("discord", "/webhooks/1")));

      Map<String, Object> result = service.test("chan-1").orElseThrow();

      assertThat(result.get("result")).isEqualTo("ok");
      wm.verify(postRequestedFor(urlEqualTo("/webhooks/1"))
          .withHeader("Content-Type", equalTo("application/json"))
          .withRequestBody(WireMock.matchingJsonPath("$.content")));
    }

    @Test
    void generic_webhook_posts_event_subject_detail_and_a_timestamp() {
      wm.stubFor(post("/hooks/aurora").willReturn(aResponse().withStatus(200)));
      Mockito.when(channelRepo.findById("chan-1")).thenReturn(java.util.Optional.of(channel("webhook", "/hooks/aurora")));

      service.test("chan-1");

      wm.verify(postRequestedFor(urlEqualTo("/hooks/aurora"))
          .withRequestBody(WireMock.matchingJsonPath("$.event"))
          .withRequestBody(WireMock.matchingJsonPath("$.subject"))
          .withRequestBody(WireMock.matchingJsonPath("$.timestamp")));
    }

    @Test
    void records_the_outcome_on_the_channel_and_in_history() {
      wm.stubFor(post("/ok").willReturn(aResponse().withStatus(200)));
      Mockito.when(channelRepo.findById("chan-1")).thenReturn(java.util.Optional.of(channel("ntfy", "/ok")));

      service.test("chan-1");

      Mockito.verify(channelRepo).recordTestResult(Mockito.eq("chan-1"), anyString(), Mockito.eq("ok"), Mockito.isNull());
      Mockito.verify(deliveryRepo).insert(anyString(), Mockito.eq("chan-1"), anyString(), anyString(), anyString(),
          Mockito.eq("ok"), Mockito.isNull());
    }
  }

  @Nested
  @DisplayName("the honest failure paths")
  class Failure {

    @Test
    void a_4xx_status_is_reported_as_failed_with_the_bodys_message() {
      wm.stubFor(post("/webhooks/1").willReturn(aResponse().withStatus(404)
          .withHeader("Content-Type", "application/json")
          .withBody("{\"message\":\"Unknown Webhook\",\"code\":10015}")));
      Mockito.when(channelRepo.findById("chan-1")).thenReturn(java.util.Optional.of(channel("discord", "/webhooks/1")));

      Map<String, Object> result = service.test("chan-1").orElseThrow();

      assertThat(result.get("result")).isEqualTo("failed");
      assertThat(result.get("error")).isEqualTo("404 Unknown Webhook");
    }

    @Test
    void a_5xx_status_with_no_body_still_reports_something_readable() {
      wm.stubFor(post("/hooks/aurora").willReturn(aResponse().withStatus(503)));
      Mockito.when(channelRepo.findById("chan-1")).thenReturn(java.util.Optional.of(channel("webhook", "/hooks/aurora")));

      Map<String, Object> result = service.test("chan-1").orElseThrow();

      assertThat(result.get("result")).isEqualTo("failed");
      assertThat((String) result.get("error")).contains("503");
    }

    @Test
    void an_unreachable_port_is_reported_as_connection_refused() {
      // Nothing is listening on this one — WireMock only stubs paths on
      // its own server, so a fixed unrelated port is genuinely dead.
      NotificationChannelRepo.Row unreachable = new NotificationChannelRepo.Row(
          "chan-1", "webhook", "Dead", "http://127.0.0.1:1", List.of("job-failed"),
          true, null, null, null);
      Mockito.when(channelRepo.findById("chan-1")).thenReturn(java.util.Optional.of(unreachable));

      Map<String, Object> result = service.test("chan-1").orElseThrow();

      assertThat(result.get("result")).isEqualTo("failed");
      assertThat((String) result.get("error")).containsIgnoringCase("refused");
    }

    @Test
    void a_slow_target_times_out_rather_than_hanging() {
      wm.stubFor(post("/slow").willReturn(aResponse().withStatus(200).withFixedDelay(6_000)));
      Mockito.when(channelRepo.findById("chan-1")).thenReturn(java.util.Optional.of(channel("webhook", "/slow")));

      Map<String, Object> result = service.test("chan-1").orElseThrow();

      assertThat(result.get("result")).isEqualTo("failed");
      assertThat((String) result.get("error")).containsIgnoringCase("timed out");
    }

    @Test
    void an_unknown_channel_id_is_reported_rather_than_thrown() {
      Mockito.when(channelRepo.findById("chan-missing")).thenReturn(java.util.Optional.empty());

      assertThat(service.test("chan-missing")).isEmpty();
    }
  }
}
