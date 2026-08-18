package com.tomaytotomato.aurora.controllers;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import com.tomaytotomato.aurora.support.AuroraIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/vpn} — Aurora's own inbound WireGuard server, against a
 * real SQLite database with only {@code wg} faked.
 *
 * <p>Covers the honest-state paths the frontend actually renders (see
 * {@code VPN_PAGE_DESIGN.md} §2's state table): not-configured, configured
 * with no peers, peers connected, a stale handshake, and the gateway
 * being down. Also pins the one hard requirement from the brief — a
 * private key must never appear in a response body it doesn't belong in,
 * nor in a log line, ever.
 */
@WithMockUser
class VpnControllerIntegrationTest extends AuroraIntegrationTest {

  private static final String WG_DUMP_INTERFACE_LINE =
      "cHJpdmF0ZWtleQ==\tc2VydmVycHVia2V5\t51820\toff";

  private String createPeer(String allowedIpsMode) throws Exception {
    MvcResult result = mvc.perform(post("/api/vpn/peers")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Bruce's phone\",\"allowedIpsMode\":\"%s\"}".formatted(allowedIpsMode)))
        .andExpect(status().isCreated())
        .andReturn();
    return result.getResponse().getContentAsString();
  }

  private void initConfig() throws Exception {
    mvc.perform(post("/api/vpn/config/init")).andExpect(status().isOk());
  }

  // ------------------------------------------------------------------

  @Nested
  @DisplayName("before any config exists")
  class NotConfigured {

    @Test
    void status_reports_not_configured() throws Exception {
      mvc.perform(get("/api/vpn/status"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.runState").value("not-configured"))
          .andExpect(jsonPath("$.interface").doesNotExist())
          .andExpect(jsonPath("$.peersTotal").value(0))
          .andExpect(jsonPath("$.peersOnline").value(0))
          .andExpect(jsonPath("$.reachable").doesNotExist());
    }

    @Test
    void config_is_a_404() throws Exception {
      mvc.perform(get("/api/vpn/config")).andExpect(status().isNotFound());
    }

    @Test
    void a_page_load_does_not_shell_out_at_all() throws Exception {
      mvc.perform(get("/api/vpn/status")).andExpect(status().isOk());
      assertThat(commands.invocations()).as("not-configured is knowable with no wg call").isEmpty();
    }

    @Test
    void init_generates_a_server_keypair() throws Exception {
      mvc.perform(post("/api/vpn/config/init"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.serverPublicKey").isNotEmpty())
          .andExpect(jsonPath("$.listenPort").value(51820))
          .andExpect(jsonPath("$.serverAddress").value("10.66.66.1/24"))
          // The hard requirement: there is no field to leak the private
          // key into in the first place.
          .andExpect(jsonPath("$.serverPrivateKey").doesNotExist());
    }

    @Test
    void init_twice_is_a_conflict() throws Exception {
      initConfig();
      mvc.perform(post("/api/vpn/config/init")).andExpect(status().isConflict());
    }

    @Test
    void rotate_key_before_init_is_not_found() throws Exception {
      mvc.perform(post("/api/vpn/server/rotate-key")).andExpect(status().isNotFound());
    }

    @Test
    void adding_a_peer_before_init_is_a_conflict() throws Exception {
      mvc.perform(post("/api/vpn/peers")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"name\":\"too early\",\"allowedIpsMode\":\"split\"}"))
          .andExpect(status().isConflict());
    }
  }

  @Nested
  @DisplayName("configured, no peers")
  class ConfiguredNoPeers {

    @BeforeEach
    void setUp() throws Exception {
      initConfig();
    }

    @Test
    void peers_list_is_empty() throws Exception {
      mvc.perform(get("/api/vpn/peers"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$").isArray())
          .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void status_is_running_with_the_gateway_reachable_and_zero_peers() throws Exception {
      commands.stubLines("wg show wg0 dump", WG_DUMP_INTERFACE_LINE);

      mvc.perform(get("/api/vpn/status"))
          .andExpect(jsonPath("$.runState").value("running"))
          .andExpect(jsonPath("$.interface").value("wg0"))
          .andExpect(jsonPath("$.peersTotal").value(0))
          .andExpect(jsonPath("$.peersOnline").value(0));
    }

    @Test
    void put_updates_the_editable_fields_without_touching_the_key() throws Exception {
      String before = mvc.perform(get("/api/vpn/config")).andReturn().getResponse().getContentAsString();
      String publicKeyBefore = JsonPath.read(before, "$.serverPublicKey");

      mvc.perform(put("/api/vpn/config")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"endpointHost\":\"aurora.example.com\",\"listenPort\":51821,"
                  + "\"dns\":\"9.9.9.9\",\"serverAddress\":\"10.77.77.1/24\",\"mtu\":1400,"
                  + "\"serverPublicKey\":\"ignored-client-supplied-value\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.endpointHost").value("aurora.example.com"))
          .andExpect(jsonPath("$.listenPort").value(51821))
          .andExpect(jsonPath("$.dns").value("9.9.9.9"))
          .andExpect(jsonPath("$.mtu").value(1400))
          // A client cannot smuggle in its own "server public key".
          .andExpect(jsonPath("$.serverPublicKey").value(publicKeyBefore));
    }

    @Test
    void rotate_key_changes_the_public_key() throws Exception {
      String before = mvc.perform(get("/api/vpn/config")).andReturn().getResponse().getContentAsString();
      String publicKeyBefore = JsonPath.read(before, "$.serverPublicKey");

      mvc.perform(post("/api/vpn/server/rotate-key"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.serverPublicKey").value(org.hamcrest.Matchers.not(publicKeyBefore)))
          .andExpect(jsonPath("$.serverPrivateKey").doesNotExist());
    }
  }

  @Nested
  @DisplayName("peers connected")
  class PeersConnected {

    @BeforeEach
    void setUp() throws Exception {
      initConfig();
    }

    @Test
    void adding_a_peer_returns_a_one_time_reveal_with_working_key_material() throws Exception {
      String body = createPeer("split");

      String privateKey = JsonPath.read(body, "$.privateKey");
      String publicKey = JsonPath.read(body, "$.peer.publicKey");
      String qr = JsonPath.read(body, "$.qrPngBase64");
      String conf = JsonPath.read(body, "$.confText");

      assertThat(privateKey).isNotBlank();
      assertThat(publicKey).isNotBlank().isNotEqualTo(privateKey);
      assertThat(qr).isNotBlank();
      assertThat(conf).contains("PrivateKey = " + privateKey);
      assertThat(conf).contains("PublicKey = "); // server's public key, in the [Peer] block
      assertThat((Boolean) JsonPath.read(body, "$.peer.killSwitch")).isFalse();
    }

    @Test
    void full_tunnel_mode_sets_the_kill_switch_and_routes_everything() throws Exception {
      String body = createPeer("full");
      assertThat((Boolean) JsonPath.read(body, "$.peer.killSwitch")).isTrue();
      assertThat((String) JsonPath.read(body, "$.peer.allowedIps")).contains("0.0.0.0/0");
    }

    @Test
    void the_peer_list_never_carries_a_private_key_field() throws Exception {
      createPeer("split");
      mvc.perform(get("/api/vpn/peers"))
          .andExpect(jsonPath("$[0].publicKey").isNotEmpty())
          .andExpect(jsonPath("$[0].privateKey").doesNotExist());
    }

    @Test
    void a_recent_handshake_counts_the_peer_as_online() throws Exception {
      String body = createPeer("split");
      String publicKey = JsonPath.read(body, "$.peer.publicKey");
      long recentEpoch = Instant.now().minus(30, ChronoUnit.SECONDS).getEpochSecond();
      commands.stubLines("wg show wg0 dump",
          WG_DUMP_INTERFACE_LINE,
          publicKey + "\t(none)\t203.0.113.5:51820\t10.66.66.2/32\t" + recentEpoch + "\t1024\t2048\toff");

      mvc.perform(get("/api/vpn/status"))
          .andExpect(jsonPath("$.runState").value("running"))
          .andExpect(jsonPath("$.peersTotal").value(1))
          .andExpect(jsonPath("$.peersOnline").value(1));

      mvc.perform(get("/api/vpn/peers"))
          .andExpect(jsonPath("$[0].lastHandshakeAt").isNotEmpty())
          .andExpect(jsonPath("$[0].rxBytes").value(1024))
          .andExpect(jsonPath("$[0].txBytes").value(2048));
    }

    @Test
    void toggling_a_peer_flips_enabled_without_deleting_it() throws Exception {
      String body = createPeer("split");
      String id = JsonPath.read(body, "$.peer.id");

      mvc.perform(post("/api/vpn/peers/{id}/toggle", id))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.enabled").value(false));

      mvc.perform(post("/api/vpn/peers/{id}/toggle", id))
          .andExpect(jsonPath("$.enabled").value(true));

      mvc.perform(get("/api/vpn/peers")).andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    void deleting_a_peer_removes_it() throws Exception {
      String body = createPeer("split");
      String id = JsonPath.read(body, "$.peer.id");

      mvc.perform(delete("/api/vpn/peers/{id}", id)).andExpect(status().isNoContent());
      mvc.perform(get("/api/vpn/peers")).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void deleting_an_unknown_peer_is_a_404() throws Exception {
      mvc.perform(delete("/api/vpn/peers/{id}", "not-a-real-id")).andExpect(status().isNotFound());
    }

    @Test
    void toggling_an_unknown_peer_is_a_404() throws Exception {
      mvc.perform(post("/api/vpn/peers/{id}/toggle", "not-a-real-id")).andExpect(status().isNotFound());
    }

    @Test
    void config_download_after_the_fact_is_a_conflict_not_a_broken_200() throws Exception {
      String body = createPeer("split");
      String id = JsonPath.read(body, "$.peer.id");

      mvc.perform(get("/api/vpn/peers/{id}/config", id)).andExpect(status().isConflict());
      mvc.perform(get("/api/vpn/peers/{id}/qrcode", id)).andExpect(status().isConflict());
    }

    @Test
    void config_download_for_an_unknown_peer_is_a_404_not_a_conflict() throws Exception {
      mvc.perform(get("/api/vpn/peers/{id}/config", "not-a-real-id")).andExpect(status().isNotFound());
    }
  }

  @Nested
  @DisplayName("a stale handshake")
  class StaleHandshake {

    @Test
    void a_peer_last_seen_over_three_minutes_ago_does_not_count_as_online() throws Exception {
      initConfig();
      String body = createPeer("split");
      String publicKey = JsonPath.read(body, "$.peer.publicKey");
      long staleEpoch = Instant.now().minus(10, ChronoUnit.MINUTES).getEpochSecond();
      commands.stubLines("wg show wg0 dump",
          WG_DUMP_INTERFACE_LINE,
          publicKey + "\t(none)\t203.0.113.5:51820\t10.66.66.2/32\t" + staleEpoch + "\t512\t512\toff");

      mvc.perform(get("/api/vpn/status"))
          .andExpect(jsonPath("$.runState").value("running"))
          .andExpect(jsonPath("$.peersTotal").value(1))
          .andExpect(jsonPath("$.peersOnline").value(0));
    }
  }

  @Nested
  @DisplayName("the gateway is down")
  class GatewayDown {

    @Test
    void status_reports_stopped_rather_than_a_stale_running_state() throws Exception {
      initConfig();
      createPeer("split");
      commands.stubMissingBinary("wg show wg0 dump");

      mvc.perform(get("/api/vpn/status"))
          .andExpect(jsonPath("$.runState").value("stopped"))
          // The peer count is still a fact Aurora knows without asking wg;
          // "online" is not, so it drops to zero rather than guessing.
          .andExpect(jsonPath("$.peersTotal").value(1))
          .andExpect(jsonPath("$.peersOnline").value(0))
          .andExpect(jsonPath("$.interface").value("wg0"));
    }
  }

  @Nested
  @DisplayName("private key hygiene")
  class PrivateKeyHygiene {

    private ListAppender<ILoggingEvent> appender;
    private Logger auroraLogger;

    /**
     * Attaches to the {@code com.tomaytotomato.aurora} logger at its
     * already-configured level (DEBUG in {@code application.yml}) rather
     * than cranking root up to {@code ALL}. That distinction matters: at
     * {@code ALL}, Spring MVC's own message-converter logging ("Writing
     * [...]") dumps the full response body at DEBUG, which would make
     * this test fail on Spring's generic behaviour rather than on
     * anything Aurora's own code does. Scoping to Aurora's package
     * matches production logging exactly and tests the thing the brief
     * actually asked for: that VpnService/VpnController never log one.
     */
    @BeforeEach
    void attachAppender() {
      auroraLogger = (Logger) org.slf4j.LoggerFactory.getLogger("com.tomaytotomato.aurora");
      appender = new ListAppender<>();
      appender.setContext(auroraLogger.getLoggerContext());
      appender.start();
      auroraLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
      if (auroraLogger != null && appender != null) {
        auroraLogger.detachAppender(appender);
      }
    }

    @Test
    void neither_the_server_nor_a_peer_private_key_is_ever_logged() throws Exception {
      initConfig();
      String configBody = mvc.perform(get("/api/vpn/config")).andReturn().getResponse().getContentAsString();
      String serverPublicKey = JsonPath.read(configBody, "$.serverPublicKey");

      mvc.perform(post("/api/vpn/server/rotate-key"));
      String peerBody = createPeer("split");
      String peerPrivateKey = JsonPath.read(peerBody, "$.privateKey");

      // Anything that could plausibly be a base64-encoded 32-byte
      // WireGuard key logged in the clear would show up verbatim; check
      // the one we definitely know (the peer's, since we hold it) never
      // appears in any captured log line, formatted message or argument.
      for (ILoggingEvent event : appender.list) {
        String formatted = event.getFormattedMessage();
        assertThat(formatted).as("log line: %s", formatted).doesNotContain(peerPrivateKey);
        if (event.getArgumentArray() != null) {
          for (Object arg : event.getArgumentArray()) {
            assertThat(String.valueOf(arg)).doesNotContain(peerPrivateKey);
          }
        }
      }
      // Sanity check the test actually exercised logging at all, so a
      // future refactor that silences VpnService entirely does not make
      // this pass for the wrong reason.
      assertThat(appender.list).isNotEmpty();
      assertThat(serverPublicKey).isNotBlank();
    }
  }

  /**
   * {@code POST /vpn/config/init} had no inverse. One click on the setup
   * screen — the only control on that card, sitting where a dismiss would
   * be — permanently flipped a box to "configured": keypair generated, no
   * endpoint, no peers, {@code wg0} absent, and no route back short of
   * editing SQLite. Found on the testbed by looking at the page, then
   * confirmed by reading the database.
   */
  @Nested
  @DisplayName("removing the configuration")
  class Removal {

    @Test
    @DisplayName("the box goes back to not-configured, so the setup screen returns")
    void removal_returns_the_box_to_not_configured() throws Exception {
      initConfig();
      mvc.perform(get("/api/vpn/config")).andExpect(status().isOk());

      mvc.perform(delete("/api/vpn/config")).andExpect(status().isNoContent());

      // Both signals the frontend reads: the 404 that drives its
      // not-configured empty state, and the run state.
      mvc.perform(get("/api/vpn/config")).andExpect(status().isNotFound());
      mvc.perform(get("/api/vpn/status"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.runState").value("not-configured"));
    }

    @Test
    @DisplayName("every peer goes too, because its .conf embeds the discarded server key")
    void removal_deletes_every_peer() throws Exception {
      initConfig();
      createPeer("split");
      createPeer("full");
      mvc.perform(get("/api/vpn/peers")).andExpect(jsonPath("$.length()").value(2));

      mvc.perform(delete("/api/vpn/config")).andExpect(status().isNoContent());

      // Leaving peers behind would be worse than deleting them: they
      // would list as valid devices whose configs authenticate against a
      // server key that no longer exists.
      mvc.perform(get("/api/vpn/peers"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("removing nothing is a 404, not a silent success")
    void removal_without_a_config_is_a_404() throws Exception {
      mvc.perform(delete("/api/vpn/config")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the door swings both ways: init works again afterwards")
    void init_works_again_after_removal() throws Exception {
      initConfig();
      mvc.perform(delete("/api/vpn/config")).andExpect(status().isNoContent());

      // Without this, "undo" would only have moved the dead end.
      mvc.perform(post("/api/vpn/config/init")).andExpect(status().isOk());
      mvc.perform(get("/api/vpn/config")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a fresh keypair, not the discarded one")
    void init_after_removal_generates_a_new_keypair() throws Exception {
      initConfig();
      String firstKey = JsonPath.read(
          mvc.perform(get("/api/vpn/config")).andReturn().getResponse().getContentAsString(),
          "$.serverPublicKey");

      mvc.perform(delete("/api/vpn/config")).andExpect(status().isNoContent());
      mvc.perform(post("/api/vpn/config/init")).andExpect(status().isOk());

      String secondKey = JsonPath.read(
          mvc.perform(get("/api/vpn/config")).andReturn().getResponse().getContentAsString(),
          "$.serverPublicKey");
      assertThat(secondKey).isNotEqualTo(firstKey);
    }
  }

  /**
   * Generating or discarding a server keypair left no trace anywhere:
   * answering "when did this box get a VPN, and who did it?" on the
   * testbed took a copy of the SQLite file and a query. Publishing an
   * mDNS alias has been audited all along; this is more consequential
   * than that.
   */
  @Nested
  @DisplayName("audit trail")
  class Audit {

    private int auditCount(String action) {
      Integer n = jdbcTemplate.queryForObject(
          "select count(*) from audit_event where action = ?", Integer.class, action);
      return n == null ? 0 : n;
    }

    @Test
    void generating_the_server_config_is_audited() throws Exception {
      initConfig();
      assertThat(auditCount("vpn.config.init")).isEqualTo(1);
    }

    @Test
    void removing_the_server_config_is_audited_with_what_it_took_out() throws Exception {
      initConfig();
      createPeer("split");
      mvc.perform(delete("/api/vpn/config")).andExpect(status().isNoContent());

      assertThat(auditCount("vpn.config.remove")).isEqualTo(1);
      String diff = jdbcTemplate.queryForObject(
          "select diff_json from audit_event where action = 'vpn.config.remove'", String.class);
      assertThat(diff)
          .as("the peer count matters: it is what the operator cannot get back")
          .contains("\"peers_deleted\":1");
    }

    @Test
    void rotating_the_server_key_is_audited() throws Exception {
      initConfig();
      mvc.perform(post("/api/vpn/server/rotate-key")).andExpect(status().isOk());
      assertThat(auditCount("vpn.server.rotate-key")).isEqualTo(1);
    }

    @Test
    void a_failed_removal_records_nothing() throws Exception {
      mvc.perform(delete("/api/vpn/config")).andExpect(status().isNotFound());
      assertThat(auditCount("vpn.config.remove")).isZero();
    }
  }
}
