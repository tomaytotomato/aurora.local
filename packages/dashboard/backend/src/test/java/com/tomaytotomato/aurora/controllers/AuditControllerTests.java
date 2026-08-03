package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuditControllerTests {

  private static MockMvc mvc(AuditEventRepo repo) {
    return MockMvcBuilders.standaloneSetup(new AuditController(repo)).build();
  }

  @Test
  void default_no_params_returns_latest_events() throws Exception {
    AuditEventRepo repo = Mockito.mock(AuditEventRepo.class);
    when(repo.query(any(), any(), any(), any(), eq(100))).thenReturn(List.of(Map.of(
        "id", 1L, "ts", "2026-08-03T09:56:00Z", "user_id", 42L,
        "action", "security.dismiss", "target", "finding:weak_admin_password:bruce",
        "diff_json", "{}")));

    mvc(repo).perform(get("/api/audit/events"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(1))
        .andExpect(jsonPath("$[0].action").value("security.dismiss"))
        .andExpect(jsonPath("$[0].user_id").value(42));

    Mockito.verify(repo).query(eq(null), eq(null), eq(null), eq(null), eq(100));
  }

  @Test
  void action_filter_forwarded_to_repo() throws Exception {
    AuditEventRepo repo = Mockito.mock(AuditEventRepo.class);
    when(repo.query(eq("security."), any(), any(), any(), any(Integer.class)))
        .thenReturn(List.of());
    mvc(repo).perform(get("/api/audit/events").param("action", "security."))
        .andExpect(status().isOk());
    Mockito.verify(repo).query(eq("security."), eq(null), eq(null), eq(null), eq(100));
  }

  @Test
  void user_and_time_range_filters_forwarded() throws Exception {
    AuditEventRepo repo = Mockito.mock(AuditEventRepo.class);
    Instant since = Instant.parse("2026-08-01T00:00:00Z");
    Instant until = Instant.parse("2026-08-03T00:00:00Z");
    when(repo.query(any(), eq(7L), eq(since), eq(until), eq(25))).thenReturn(List.of());
    mvc(repo).perform(get("/api/audit/events")
            .param("userId", "7")
            .param("since", "2026-08-01T00:00:00Z")
            .param("until", "2026-08-03T00:00:00Z")
            .param("limit", "25"))
        .andExpect(status().isOk());
    Mockito.verify(repo).query(eq(null), eq(7L), eq(since), eq(until), eq(25));
  }

  @Test
  void rejects_malformed_action() throws Exception {
    AuditEventRepo repo = Mockito.mock(AuditEventRepo.class);
    for (String bad : new String[] { "UPPER", "1leading", "has$dollar", "has=eq" }) {
      mvc(repo).perform(get("/api/audit/events").param("action", bad))
          .andExpect(status().isBadRequest());
    }
    Mockito.verifyNoInteractions(repo);
  }

  @Test
  void rejects_negative_user_id() throws Exception {
    AuditEventRepo repo = Mockito.mock(AuditEventRepo.class);
    mvc(repo).perform(get("/api/audit/events").param("userId", "-1"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejects_bad_iso_timestamps() throws Exception {
    AuditEventRepo repo = Mockito.mock(AuditEventRepo.class);
    for (String bad : new String[] { "not-a-date", "2026-13-01T00:00:00Z", "" + Long.MAX_VALUE }) {
      // Empty string is treated as null (allowed) — that path is exercised
      // by default_no_params_returns_latest_events. Here we exercise
      // malformed non-empty strings only.
      mvc(repo).perform(get("/api/audit/events").param("since", bad))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  void rejects_since_not_before_until() throws Exception {
    AuditEventRepo repo = Mockito.mock(AuditEventRepo.class);
    mvc(repo).perform(get("/api/audit/events")
            .param("since", "2026-08-03T00:00:00Z")
            .param("until", "2026-08-03T00:00:00Z"))
        .andExpect(status().isBadRequest());
    mvc(repo).perform(get("/api/audit/events")
            .param("since", "2026-08-04T00:00:00Z")
            .param("until", "2026-08-03T00:00:00Z"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejects_out_of_range_limit() throws Exception {
    AuditEventRepo repo = Mockito.mock(AuditEventRepo.class);
    for (int bad : new int[] { 0, -1, 501, 100_000 }) {
      mvc(repo).perform(get("/api/audit/events").param("limit", String.valueOf(bad)))
          .andExpect(status().isBadRequest());
    }
  }
}
