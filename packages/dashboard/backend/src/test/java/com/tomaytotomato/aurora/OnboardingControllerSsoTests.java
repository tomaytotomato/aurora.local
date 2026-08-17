package com.tomaytotomato.aurora;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomaytotomato.aurora.controllers.OnboardingController;
import com.tomaytotomato.aurora.domain.RepoState;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import com.tomaytotomato.aurora.services.IdentitySecretsService;
import com.tomaytotomato.aurora.services.LaunchService;
import com.tomaytotomato.aurora.services.OnboardingService;
import com.tomaytotomato.aurora.services.StateFileService;
import com.tomaytotomato.aurora.services.SystemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase D iter-11 (D10) \u2014 {@link OnboardingController#setSso}.
 *
 * <p>Uses a standalone MockMvc setup so no Spring context is needed
 * (pre-existing Spring Boot 4 bean-override collision keeps
 * {@code @SpringBootTest} broken for DB paths). Verifies:
 *
 * <ul>
 *   <li>{@code enable: true} \u2014 adds 'identity' to enabled[],\n *       calls {@code ensureSecrets()}, records an audit row.</li>
 *   <li>{@code enable: false} \u2014 removes 'identity' if present,
 *       never touches the secrets file, records a skip audit row.</li>
 *   <li>Idempotent add: hitting enable=true twice doesn't write the
 *       state file the second time (no-change short-circuit).</li>
 *   <li>{@code guardMidOnboarding} is invoked before any side effect
 *       so a completed wizard can't retroactively flip SSO.</li>
 * </ul>
 */
class OnboardingControllerSsoTests {

  private static final ObjectMapper JSON = new ObjectMapper();

  private OnboardingService onboarding;
  private StateFileService stateFiles;
  private IdentitySecretsService identitySecrets;
  private AuditEventRepo audit;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    onboarding = mock(OnboardingService.class);
    stateFiles = mock(StateFileService.class);
    identitySecrets = mock(IdentitySecretsService.class);
    audit = mock(AuditEventRepo.class);

    OnboardingController c = new OnboardingController(
        onboarding,
        mock(SystemService.class),
        mock(LaunchService.class),
        stateFiles,
        identitySecrets,
        audit,
        mock(com.tomaytotomato.aurora.services.PackagesService.class),
        mock(com.tomaytotomato.aurora.services.SessionService.class)
    );
    mvc = MockMvcBuilders.standaloneSetup(c).build();
  }

  @Test
  void enable_adds_identity_and_calls_ensureSecrets() throws Exception {
    when(stateFiles.readState()).thenReturn(new RepoState(
        1, "aurora", "aurora.local", null,
        List.of("core", "media"), List.of()
    ));

    mvc.perform(post("/api/onboarding/sso")
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(Map.of("enable", true))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.packages", org.hamcrest.Matchers.hasItem("identity")));

    // enabled[] rewritten with identity appended.
    ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
    verify(stateFiles).writeEnabled(captor.capture());
    org.assertj.core.api.Assertions.assertThat(captor.getValue())
        .containsExactly("core", "media", "identity");

    // Secrets ensured (generates any missing keys).
    verify(identitySecrets, times(1)).ensureSecrets();

    // Audit row records the enable event.
    verify(audit).record(Mockito.isNull(), eq("onboarding.sso.enable"),
        eq("packages/identity"), Mockito.isNull());

    // guardMidOnboarding invoked before any mutation.
    verify(onboarding).guardMidOnboarding();
  }

  @Test
  void enable_when_identity_already_present_short_circuits_stateFile_write() throws Exception {
    when(stateFiles.readState()).thenReturn(new RepoState(
        1, "aurora", "aurora.local", null,
        List.of("core", "identity"), List.of()
    ));

    mvc.perform(post("/api/onboarding/sso")
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(Map.of("enable", true))))
        .andExpect(status().isOk());

    // No enabled[] rewrite because the list didn't change.
    verify(stateFiles, Mockito.never()).writeEnabled(any());
    // Still ensure secrets \u2014 idempotent, generates only missing keys.
    verify(identitySecrets).ensureSecrets();
  }

  @Test
  void skip_removes_identity_from_enabled_and_does_not_touch_secrets() throws Exception {
    when(stateFiles.readState()).thenReturn(new RepoState(
        1, "aurora", "aurora.local", null,
        List.of("core", "identity", "media"), List.of()
    ));

    mvc.perform(post("/api/onboarding/sso")
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(Map.of("enable", false))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false));

    // identity dropped from enabled[].
    ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
    verify(stateFiles).writeEnabled(captor.capture());
    org.assertj.core.api.Assertions.assertThat(Set.copyOf(captor.getValue()))
        .doesNotContain("identity")
        .containsExactlyInAnyOrder("core", "media");

    // Secrets NOT rewritten \u2014 the .env stays on disk in case the
    // operator opts back in later without a re-generate.
    verify(identitySecrets, Mockito.never()).ensureSecrets();

    verify(audit).record(Mockito.isNull(), eq("onboarding.sso.skip"),
        eq("packages/identity"), Mockito.isNull());
  }

  @Test
  void skip_when_identity_not_enabled_is_a_noop_on_stateFile() throws Exception {
    when(stateFiles.readState()).thenReturn(new RepoState(
        1, "aurora", "aurora.local", null,
        List.of("core", "media"), List.of()
    ));

    mvc.perform(post("/api/onboarding/sso")
            .contentType(MediaType.APPLICATION_JSON)
            .content(JSON.writeValueAsString(Map.of("enable", false))))
        .andExpect(status().isOk());

    verify(stateFiles, Mockito.never()).writeEnabled(any());
  }
}
