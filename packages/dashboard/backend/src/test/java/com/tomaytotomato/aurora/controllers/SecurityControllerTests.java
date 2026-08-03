package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.SecurityFinding;
import com.tomaytotomato.aurora.security.SecurityFindingsService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B4 (v0.3): {@code GET /api/security/findings} shape. Standalone
 * MockMvc; SecurityFindingsService mocked.
 */
class SecurityControllerTests {

  @Test
  void returns_findings_list_with_all_fields() throws Exception {
    SecurityFindingsService svc = Mockito.mock(SecurityFindingsService.class);
    when(svc.allFindings()).thenReturn(List.of(
        new SecurityFinding("weak_admin_password:bruce", SecurityFinding.HIGH,
            "Admin password uses weak protection parameters",
            "Rotate the admin password on the Settings page.",
            "/settings#account")));

    MockMvc mvc = MockMvcBuilders.standaloneSetup(new SecurityController(svc)).build();
    mvc.perform(get("/api/security/findings"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("weak_admin_password:bruce"))
        .andExpect(jsonPath("$[0].severity").value("high"))
        .andExpect(jsonPath("$[0].title").value("Admin password uses weak protection parameters"))
        .andExpect(jsonPath("$[0].description").value("Rotate the admin password on the Settings page."))
        .andExpect(jsonPath("$[0].remediationUrl").value("/settings#account"));
  }

  @Test
  void empty_findings_yield_empty_array() throws Exception {
    SecurityFindingsService svc = Mockito.mock(SecurityFindingsService.class);
    when(svc.allFindings()).thenReturn(List.of());

    MockMvc mvc = MockMvcBuilders.standaloneSetup(new SecurityController(svc)).build();
    mvc.perform(get("/api/security/findings"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }
}
