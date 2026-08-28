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
 * The DNS step of the wizard has to carry its own package.
 *
 * <p>Choosing "AdGuard on this box" used to set a string on the draft and
 * nothing else: the step promised "Install the privacy package (AdGuard
 * Home)", the Review step then warned "DNS mode is 'adguard' but the privacy
 * package ... is not selected" with no way to act on it, and Install went
 * ahead regardless — leaving a box whose chosen DNS story simply did not
 * exist. These tests pin the fix at both ends: the preview shows the package
 * before anything is written, and the install persists it.
 */
class OnboardingDnsImpliesPackageIntegrationTest extends AuroraIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private void createAdmin() throws Exception {
    mvc.perform(post("/api/onboarding/admin")
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(Map.of(
                "username", "admin",
                "password", "a-very-strong-passw0rd",
                "tz", "UTC"))))
        .andExpect(status().isOk());
  }

  private void setDnsMode(String mode) throws Exception {
    mvc.perform(patch("/api/onboarding")
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(Map.of("dns_mode", mode))))
        .andExpect(status().isOk());
  }

  private void writePrivacyManifest() throws Exception {
    // The shared fake-repo has no privacy package; give it one so "the
    // package exists" is the case under test.
    writeRepoFile("packages/privacy/manifest.yml",
        "name: privacy\n"
            + "title: Privacy (LAN DNS + VPN)\n"
            + "description: AdGuard Home\n"
            + "category: privacy\n"
            + "depends_on: [core]\n");
  }

  private JsonNode plan() throws Exception {
    MvcResult result = mvc.perform(get("/api/onboarding/plan"))
        .andExpect(status().isOk()).andReturn();
    return JSON.readTree(result.getResponse().getContentAsString());
  }

  private List<String> texts(JsonNode array) {
    var out = new ArrayList<String>();
    array.forEach(n -> out.add(n.asText()));
    return out;
  }

  @Test
  void planIncludesAdguardsPackageAndDropsTheContradictoryWarning() throws Exception {
    createAdmin();
    writePrivacyManifest();
    setDnsMode("adguard");

    var plan = plan();

    assertThat(texts(plan.get("packages_to_enable"))).contains("privacy");
    assertThat(texts(plan.get("warnings")))
        .noneMatch(w -> w.contains("is not selected"));
  }

  @Test
  void installPersistsAdguardsPackageAndSaysWhy() throws Exception {
    createAdmin();
    writePrivacyManifest();
    setDnsMode("adguard");

    MvcResult result = mvc.perform(post("/api/onboarding/install"))
        .andExpect(status().isOk()).andReturn();
    var body = JSON.readTree(result.getResponse().getContentAsString());

    assertThat(texts(body.get("applied")))
        .anyMatch(line -> line.contains("because you chose AdGuard for DNS"));

    // ...and it is in the persisted plan, which is what /launch hands to up.sh.
    assertThat(texts(plan().get("packages_to_enable"))).contains("privacy");
  }

  @Test
  void otherDnsStoriesAddNothing() throws Exception {
    createAdmin();
    writePrivacyManifest();
    setDnsMode("router");

    assertThat(texts(plan().get("packages_to_enable"))).doesNotContain("privacy");
  }

  @Test
  void aMissingAdguardPackageWarnsInTheUsersLanguage() throws Exception {
    createAdmin();
    // No privacy manifest written: the repo cannot honour the choice.
    setDnsMode("adguard");

    assertThat(texts(plan().get("warnings")))
        .anyMatch(w -> w.contains("AdGuard isn't available in this build")
            && w.contains("Point your devices at your router's DNS"));
  }
}
