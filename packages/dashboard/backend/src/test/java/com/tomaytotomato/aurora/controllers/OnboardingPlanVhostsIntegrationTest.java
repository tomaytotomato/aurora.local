package com.tomaytotomato.aurora.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomaytotomato.aurora.support.AuroraIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the Review step promises the box's addresses will be.
 *
 * <p>Two of these are regressions caught by walking a real install rather
 * than by reading code: the doubled {@code aurora.aurora.local} (which the
 * design language names explicitly as a thing that must never be shown)
 * appeared the moment {@code dashboard} correctly entered the enabled set,
 * and {@code qbittorrent.aurora.local} was promised for a service behind a
 * compose profile that the box does not turn on.
 */
class OnboardingPlanVhostsIntegrationTest extends AuroraIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private List<String> vhosts(String enabledCsv) throws Exception {
    MvcResult r = mvc.perform(get("/api/onboarding/plan").param("enabled", enabledCsv))
        .andExpect(status().isOk()).andReturn();
    JsonNode body = JSON.readTree(r.getResponse().getContentAsString());
    var out = new ArrayList<String>();
    body.get("vhosts").forEach(n -> out.add(n.asText()));
    return out;
  }

  private void createAdmin() throws Exception {
    mvc.perform(post("/api/onboarding/admin")
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(Map.of(
                "username", "admin", "password", "a-very-strong-passw0rd", "tz", "UTC"))))
        .andExpect(status().isOk());
  }

  private void setDomain() throws Exception {
    mvc.perform(patch("/api/onboarding")
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(Map.of("domain", "aurora.local"))))
        .andExpect(status().isOk());
  }

  @Test
  void theDashboardNeverAppearsAsASubdomainOfItsOwnDomain() throws Exception {
    writeRepoFile("packages/dashboard/manifest.yml",
        "name: dashboard\ntitle: Aurora\ndescription: admin\ncategory: core\n"
            + "ports:\n  - {port: 8090, proto: tcp, description: Aurora}\n");
    createAdmin();
    setDomain();

    var vhosts = vhosts("core,dashboard");
    assertThat(vhosts).doesNotContain("aurora.aurora.local");
    // The apex and admin. are still there — they are what the dashboard is.
    assertThat(vhosts).contains("aurora.local", "admin.aurora.local");
  }

  @Test
  void aProfileGatedServiceIsNotPromisedAnAddress() throws Exception {
    writeRepoFile("packages/privacy/manifest.yml",
        "name: privacy\ntitle: Privacy\ndescription: dns\ncategory: privacy\n"
            + "ports:\n"
            + "  - {port: 3000, proto: tcp, description: AdGuard}\n"
            + "  - {port: 8080, proto: tcp, description: qBittorrent WebUI, profile: torrent}\n");
    createAdmin();
    setDomain();

    var vhosts = vhosts("core,privacy");
    assertThat(vhosts).contains("adguard.aurora.local");
    assertThat(vhosts).doesNotContain("qbittorrent.aurora.local");
  }
}
