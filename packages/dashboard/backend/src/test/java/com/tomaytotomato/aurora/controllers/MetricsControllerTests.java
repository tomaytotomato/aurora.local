package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.persistence.MetricsRepo;
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

/**
 * B2 (v0.3): {@link MetricsController#last24h(String, int)} shape +
 * validation. Standalone MockMvc — no Spring context, matches the
 * pattern in HealthControllerTests.
 */
class MetricsControllerTests {

  @Test
  void returns_bucketed_series_for_valid_key() throws Exception {
    MetricsRepo repo = Mockito.mock(MetricsRepo.class);
    when(repo.bucketed24h(eq("sys.cpu_pct"), eq(5), any(Instant.class)))
        .thenReturn(List.of(
            Map.of("ts", 1_735_689_000_000L, "avg", 12.4, "min", 8.1, "max", 18.9, "count", 10)));

    MockMvc mvc = MockMvcBuilders.standaloneSetup(new MetricsController(repo)).build();
    mvc.perform(get("/api/metrics/last24h").param("key", "sys.cpu_pct"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].ts").value(1_735_689_000_000L))
        .andExpect(jsonPath("$[0].avg").value(12.4))
        .andExpect(jsonPath("$[0].min").value(8.1))
        .andExpect(jsonPath("$[0].max").value(18.9))
        .andExpect(jsonPath("$[0].count").value(10));
  }

  @Test
  void defaults_to_5_minute_buckets() throws Exception {
    MetricsRepo repo = Mockito.mock(MetricsRepo.class);
    when(repo.bucketed24h(eq("sys.cpu_pct"), eq(5), any(Instant.class)))
        .thenReturn(List.of());

    MockMvc mvc = MockMvcBuilders.standaloneSetup(new MetricsController(repo)).build();
    mvc.perform(get("/api/metrics/last24h").param("key", "sys.cpu_pct"))
        .andExpect(status().isOk());
    // Verify the 5-minute bucket is what the repo actually got.
    Mockito.verify(repo).bucketed24h(eq("sys.cpu_pct"), eq(5), any(Instant.class));
  }

  @Test
  void allows_all_wall_clock_aligned_buckets() throws Exception {
    MetricsRepo repo = Mockito.mock(MetricsRepo.class);
    when(repo.bucketed24h(any(String.class), any(Integer.class), any(Instant.class)))
        .thenReturn(List.of());
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new MetricsController(repo)).build();
    for (int minutes : new int[] { 1, 2, 5, 10, 15, 30, 60 }) {
      mvc.perform(get("/api/metrics/last24h")
              .param("key", "sys.cpu_pct")
              .param("bucketMinutes", String.valueOf(minutes)))
          .andExpect(status().isOk());
    }
  }

  @Test
  void rejects_malformed_key() throws Exception {
    MetricsRepo repo = Mockito.mock(MetricsRepo.class);
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new MetricsController(repo)).build();
    // Uppercase, spaces, path-traversal shapes, injection-like input.
    for (String bad : new String[] {
        "Sys.Cpu",
        "sys cpu",
        "../etc/passwd",
        "sys.cpu; drop table metric_sample",
        "1st.key",
        ""
    }) {
      mvc.perform(get("/api/metrics/last24h").param("key", bad))
          .andExpect(status().isBadRequest());
    }
    Mockito.verifyNoInteractions(repo);
  }

  @Test
  void rejects_non_aligned_bucket_widths() throws Exception {
    MetricsRepo repo = Mockito.mock(MetricsRepo.class);
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new MetricsController(repo)).build();
    for (int bad : new int[] { 0, 3, 7, 45, 90, 120, -5 }) {
      mvc.perform(get("/api/metrics/last24h")
              .param("key", "sys.cpu_pct")
              .param("bucketMinutes", String.valueOf(bad)))
          .andExpect(status().isBadRequest());
    }
  }

  // -- /keys discovery (B2-followup iter-21) ---------------------------

  @Test
  void keys_no_prefix_returns_all_distinct_keys() throws Exception {
    MetricsRepo repo = Mockito.mock(MetricsRepo.class);
    when(repo.distinctKeys(null))
        .thenReturn(List.of("app.uptime_ms", "sys.cpu_pct", "container.aurora.cpu_pct"));
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new MetricsController(repo)).build();
    mvc.perform(get("/api/metrics/keys"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0]").value("app.uptime_ms"));
  }

  @Test
  void keys_with_prefix_delegates_to_repo() throws Exception {
    MetricsRepo repo = Mockito.mock(MetricsRepo.class);
    when(repo.distinctKeys("container."))
        .thenReturn(List.of("container.aurora.cpu_pct", "container.aurora.mem_used_bytes"));
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new MetricsController(repo)).build();
    mvc.perform(get("/api/metrics/keys").param("prefix", "container."))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void keys_rejects_malformed_prefix() throws Exception {
    MetricsRepo repo = Mockito.mock(MetricsRepo.class);
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new MetricsController(repo)).build();
    for (String bad : new String[] {
        "UPPER",
        "has space",
        "1leading",
        "drop;table",
        "container.%%",
    }) {
      mvc.perform(get("/api/metrics/keys").param("prefix", bad))
          .andExpect(status().isBadRequest());
    }
    Mockito.verifyNoInteractions(repo);
  }

  @Test
  void keys_empty_prefix_returns_all_keys() throws Exception {
    // ?prefix= is treated as null (skips validation, delegates to repo).
    MetricsRepo repo = Mockito.mock(MetricsRepo.class);
    when(repo.distinctKeys("")).thenReturn(List.of("a"));
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new MetricsController(repo)).build();
    mvc.perform(get("/api/metrics/keys").param("prefix", ""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
  }
}
