package com.tomaytotomato.aurora.controllers;

import com.jayway.jsonpath.JsonPath;
import com.tomaytotomato.aurora.support.AuroraIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/vpn/openvpn} — the secondary, de-emphasised server. No
 * {@code CommandRunner} involved: this domain is pure config/bookkeeping,
 * see {@code OpenVpnService} javadoc for why.
 */
@WithMockUser
class OpenVpnControllerIntegrationTest extends AuroraIntegrationTest {

  @Nested
  @DisplayName("config")
  class Config {

    @Test
    void defaults_to_disabled_udp_1194() throws Exception {
      mvc.perform(get("/api/vpn/openvpn/config"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.enabled").value(false))
          .andExpect(jsonPath("$.port").value(1194))
          .andExpect(jsonPath("$.protocol").value("udp"));
    }

    @Test
    void put_updates_and_persists() throws Exception {
      mvc.perform(put("/api/vpn/openvpn/config")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"enabled\":true,\"port\":11940,\"protocol\":\"tcp\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.enabled").value(true))
          .andExpect(jsonPath("$.port").value(11940))
          .andExpect(jsonPath("$.protocol").value("tcp"));

      mvc.perform(get("/api/vpn/openvpn/config"))
          .andExpect(jsonPath("$.enabled").value(true))
          .andExpect(jsonPath("$.port").value(11940));
    }

    @Test
    void rejects_an_unknown_protocol() throws Exception {
      mvc.perform(put("/api/vpn/openvpn/config")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"enabled\":true,\"port\":1194,\"protocol\":\"sctp\"}"))
          .andExpect(status().isBadRequest());
    }
  }

  @Nested
  @DisplayName("clients")
  class Clients {

    @Test
    void starts_empty() throws Exception {
      mvc.perform(get("/api/vpn/openvpn/clients"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void adding_a_client_returns_the_client_and_a_conf_text_with_no_qr_field() throws Exception {
      String body = mvc.perform(post("/api/vpn/openvpn/clients")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"name\":\"laptop\"}"))
          .andExpect(status().isCreated())
          .andReturn().getResponse().getContentAsString();

      String name = JsonPath.read(body, "$.client.name");
      String conf = JsonPath.read(body, "$.confText");
      org.assertj.core.api.Assertions.assertThat(name).isEqualTo("laptop");
      org.assertj.core.api.Assertions.assertThat(conf).isNotBlank();
      // No QR code for OpenVPN — deliberately less UI than WireGuard.
      org.assertj.core.api.Assertions.assertThat(body).doesNotContain("qrPngBase64");
    }

    @Test
    void rejects_a_blank_name() throws Exception {
      mvc.perform(post("/api/vpn/openvpn/clients")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"name\":\"\"}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    void deleting_a_client_removes_it() throws Exception {
      String body = mvc.perform(post("/api/vpn/openvpn/clients")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"name\":\"laptop\"}"))
          .andReturn().getResponse().getContentAsString();
      String id = JsonPath.read(body, "$.client.id");

      mvc.perform(delete("/api/vpn/openvpn/clients/{id}", id)).andExpect(status().isNoContent());
      mvc.perform(get("/api/vpn/openvpn/clients")).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void deleting_an_unknown_client_is_a_404() throws Exception {
      mvc.perform(delete("/api/vpn/openvpn/clients/{id}", "no-such-id")).andExpect(status().isNotFound());
    }
  }
}
