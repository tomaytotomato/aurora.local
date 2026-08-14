package com.tomaytotomato.aurora.controllers;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.tomaytotomato.aurora.support.AuroraIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/notifications} against a real SQLite {@code
 * notification_channel}/{@code notification_delivery} pair and a real
 * WireMock server standing in for ntfy/Discord/webhook targets.
 */
@WithMockUser
class NotificationsControllerIntegrationTest extends AuroraIntegrationTest {

  @RegisterExtension
  static WireMockExtension wm = WireMockExtension.newInstance().build();

  private String createChannel(String kind, String path, String... events) throws Exception {
    String eventsJson = String.join(",", java.util.Arrays.stream(events).map(e -> "\"" + e + "\"").toArray(String[]::new));
    String body = mvc.perform(post("/api/notifications/channels").contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"" + kind + "\",\"name\":\"Test\",\"target\":\""
                + wm.baseUrl() + path + "\",\"events\":[" + eventsJson + "]}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return com.jayway.jsonpath.JsonPath.read(body, "$.id");
  }

  @Nested
  @DisplayName("POST /channels — creating a channel")
  class Create {

    @Test
    void creates_an_enabled_channel_with_no_history_yet() throws Exception {
      mvc.perform(post("/api/notifications/channels").contentType(MediaType.APPLICATION_JSON)
              .content("{\"kind\":\"ntfy\",\"name\":\"Phone\",\"target\":\"https://ntfy.sh/aurora-topic\","
                  + "\"events\":[\"service-down\",\"backup-failed\"]}"))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.startsWith("chan-")))
          .andExpect(jsonPath("$.kind").value("ntfy"))
          .andExpect(jsonPath("$.enabled").value(true))
          .andExpect(jsonPath("$.lastSentAt").value((Object) null))
          .andExpect(jsonPath("$.lastResult").value((Object) null))
          .andExpect(jsonPath("$.events[0]").value("service-down"));
    }

    @Test
    void refuses_an_unknown_channel_kind() throws Exception {
      mvc.perform(post("/api/notifications/channels").contentType(MediaType.APPLICATION_JSON)
              .content("{\"kind\":\"carrier-pigeon\",\"name\":\"Bird\",\"target\":\"https://example.com\","
                  + "\"events\":[\"job-failed\"]}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    void refuses_a_target_that_is_not_a_url() throws Exception {
      mvc.perform(post("/api/notifications/channels").contentType(MediaType.APPLICATION_JSON)
              .content("{\"kind\":\"webhook\",\"name\":\"Bad\",\"target\":\"not-a-url\","
                  + "\"events\":[\"job-failed\"]}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    void refuses_a_channel_with_no_events() throws Exception {
      mvc.perform(post("/api/notifications/channels").contentType(MediaType.APPLICATION_JSON)
              .content("{\"kind\":\"webhook\",\"name\":\"Quiet\",\"target\":\"https://example.com/hook\","
                  + "\"events\":[]}"))
          .andExpect(status().isBadRequest());
    }
  }

  @Nested
  @DisplayName("GET /channels")
  class List_ {

    @Test
    void lists_every_configured_channel() throws Exception {
      createChannel("ntfy", "/topic1", "service-down");
      createChannel("discord", "/webhooks/1", "job-failed");

      mvc.perform(get("/api/notifications/channels"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(2));
    }
  }

  @Nested
  @DisplayName("PATCH /channels/{id} — change or mute")
  class Update {

    @Test
    void muting_only_sends_enabled_and_leaves_everything_else_alone() throws Exception {
      String id = createChannel("ntfy", "/topic", "service-down");

      mvc.perform(patch("/api/notifications/channels/{id}", id).contentType(MediaType.APPLICATION_JSON)
              .content("{\"enabled\":false}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.enabled").value(false))
          .andExpect(jsonPath("$.kind").value("ntfy"))
          .andExpect(jsonPath("$.events[0]").value("service-down"));
    }

    @Test
    void can_change_the_target_and_events_together() throws Exception {
      String id = createChannel("webhook", "/old", "job-failed");

      mvc.perform(patch("/api/notifications/channels/{id}", id).contentType(MediaType.APPLICATION_JSON)
              .content("{\"target\":\"" + wm.baseUrl() + "/new\",\"events\":[\"disk-health\"]}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.target").value(wm.baseUrl() + "/new"))
          .andExpect(jsonPath("$.events[0]").value("disk-health"));
    }

    @Test
    void a_404_for_an_unknown_channel() throws Exception {
      mvc.perform(patch("/api/notifications/channels/{id}", "chan-does-not-exist")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"enabled\":false}"))
          .andExpect(status().isNotFound());
    }
  }

  @Nested
  @DisplayName("DELETE /channels/{id}")
  class Delete {

    @Test
    void removes_a_channel() throws Exception {
      String id = createChannel("ntfy", "/topic", "service-down");

      mvc.perform(delete("/api/notifications/channels/{id}", id)).andExpect(status().isNoContent());

      mvc.perform(get("/api/notifications/channels")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void an_unknown_id_is_still_no_content() throws Exception {
      mvc.perform(delete("/api/notifications/channels/{id}", "chan-does-not-exist"))
          .andExpect(status().isNoContent());
    }
  }

  @Nested
  @DisplayName("POST /channels/{id}/test — an honest test send")
  class TestSend {

    @Test
    void a_working_ntfy_target_reports_ok() throws Exception {
      wm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/topic").willReturn(aResponse().withStatus(200)));
      String id = createChannel("ntfy", "/topic", "service-down");

      mvc.perform(post("/api/notifications/channels/{id}/test", id))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.result").value("ok"))
          .andExpect(jsonPath("$.error").value((Object) null));

      mvc.perform(get("/api/notifications/channels"))
          .andExpect(jsonPath("$[0].lastResult").value("ok"))
          .andExpect(jsonPath("$[0].lastSentAt").isNotEmpty());
    }

    @Test
    void a_deleted_discord_webhook_reports_failed_with_the_real_reason() throws Exception {
      wm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/webhooks/dead").willReturn(aResponse().withStatus(404)
          .withHeader("Content-Type", "application/json")
          .withBody("{\"message\":\"Unknown Webhook\",\"code\":10015}")));
      String id = createChannel("discord", "/webhooks/dead", "job-failed");

      mvc.perform(post("/api/notifications/channels/{id}/test", id))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.result").value("failed"))
          .andExpect(jsonPath("$.error").value("404 Unknown Webhook"));
    }

    @Test
    void appears_in_history_afterwards() throws Exception {
      wm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/topic").willReturn(aResponse().withStatus(200)));
      String id = createChannel("ntfy", "/topic", "service-down");

      mvc.perform(post("/api/notifications/channels/{id}/test", id)).andExpect(status().isOk());

      mvc.perform(get("/api/notifications/history"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].channelId").value(id))
          .andExpect(jsonPath("$[0].result").value("ok"))
          .andExpect(jsonPath("$[0].event").value("job-failed"));
    }

    @Test
    void a_404_for_an_unknown_channel() throws Exception {
      mvc.perform(post("/api/notifications/channels/{id}/test", "chan-does-not-exist"))
          .andExpect(status().isNotFound());
    }

    @Test
    void history_survives_the_channel_being_deleted_afterwards() throws Exception {
      wm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/topic").willReturn(aResponse().withStatus(200)));
      String id = createChannel("ntfy", "/topic", "service-down");
      mvc.perform(post("/api/notifications/channels/{id}/test", id)).andExpect(status().isOk());

      mvc.perform(delete("/api/notifications/channels/{id}", id)).andExpect(status().isNoContent());

      mvc.perform(get("/api/notifications/history"))
          .andExpect(jsonPath("$[0].channelId").value(id));
    }
  }
}
