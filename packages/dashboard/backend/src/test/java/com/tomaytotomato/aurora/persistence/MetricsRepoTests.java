package com.tomaytotomato.aurora.persistence;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * B2 (v0.3): {@link MetricsRepo} SQL contract. Mockito-over-JdbcTemplate
 * pattern used elsewhere in the suite (see HealthControllerTests). A
 * dedicated integration test against an in-memory SQLite lands as a
 * follow-up so the bucket SQL is exercised end-to-end, but the mocked
 * unit pins the argv the repo hands JdbcTemplate.
 */
class MetricsRepoTests {

  @Test
  void insert_writes_upsert_row_with_iso_ts() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    MetricsRepo repo = new MetricsRepo(jdbc);
    Instant now = Instant.parse("2026-08-03T08:15:00Z");
    repo.insert(now, "sys.cpu_pct", 42.7);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    Mockito.verify(jdbc).update(sql.capture(),
        eq(now.toString()), eq("sys.cpu_pct"), eq(42.7));
    assertTrue(sql.getValue().startsWith("INSERT OR REPLACE INTO metric_sample"),
        "SQL was: " + sql.getValue());
  }

  @Test
  void insert_swallows_jdbc_failures() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    Mockito.when(jdbc.update(anyString(), any(Object[].class)))
        .thenThrow(new RuntimeException("db down"));
    Mockito.doThrow(new RuntimeException("db down"))
        .when(jdbc).update(anyString(), any(), any(), any());
    MetricsRepo repo = new MetricsRepo(jdbc);
    // Must not propagate — one bad insert should not knock the sampler
    // out of the schedule loop.
    repo.insert(Instant.now(), "sys.cpu_pct", 1.0);
  }

  @Test
  void insertBatch_emits_one_batchUpdate_call() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    MetricsRepo repo = new MetricsRepo(jdbc);
    Instant now = Instant.parse("2026-08-03T08:15:00Z");
    repo.insertBatch(now, Map.of(
        "sys.cpu_pct", 12.3,
        "sys.mem_used_bytes", 4_000_000_000d
    ));
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    @SuppressWarnings({"unchecked", "rawtypes"})
    ArgumentCaptor<List<Object[]>> args = ArgumentCaptor.forClass((Class) List.class);
    Mockito.verify(jdbc).batchUpdate(sql.capture(), args.capture());
    assertEquals(2, args.getValue().size());
    assertTrue(sql.getValue().contains("metric_sample"));
  }

  @Test
  void insertBatch_no_op_on_empty_map() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    MetricsRepo repo = new MetricsRepo(jdbc);
    repo.insertBatch(Instant.now(), Map.of());
    Mockito.verifyNoInteractions(jdbc);
  }

  @Test
  void insertBatch_no_op_on_null_map() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    MetricsRepo repo = new MetricsRepo(jdbc);
    repo.insertBatch(Instant.now(), null);
    Mockito.verifyNoInteractions(jdbc);
  }

  @Test
  void pruneOlderThan_deletes_by_cutoff() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    Instant cutoff = Instant.parse("2026-08-02T08:15:00Z");
    Mockito.when(jdbc.update(anyString(), eq(cutoff.toString()))).thenReturn(17);
    MetricsRepo repo = new MetricsRepo(jdbc);
    int n = repo.pruneOlderThan(cutoff);
    assertEquals(17, n);
    Mockito.verify(jdbc).update(
        "DELETE FROM metric_sample WHERE ts < ?", cutoff.toString());
  }

  @Test
  void pruneOlderThan_returns_zero_on_failure() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    Mockito.when(jdbc.update(anyString(), anyString()))
        .thenThrow(new RuntimeException("locked"));
    MetricsRepo repo = new MetricsRepo(jdbc);
    assertEquals(0, repo.pruneOlderThan(Instant.now()));
  }

  @Test
  void bucketed24h_calls_query_with_key_and_cutoff() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    Instant now = Instant.parse("2026-08-03T08:15:00Z");
    Instant expectedCutoff = now.minusSeconds(24 * 3600L);
    long bucketMs = 5 * 60_000L;

    Mockito.when(jdbc.query(
        anyString(),
        Mockito.<RowMapper<Object>>any(),
        eq(bucketMs), eq(bucketMs), eq("sys.cpu_pct"), eq(expectedCutoff.toString())
    )).thenReturn(List.of());

    MetricsRepo repo = new MetricsRepo(jdbc);
    List<Map<String, Object>> result = repo.bucketed24h("sys.cpu_pct", 5, now);
    assertEquals(List.of(), result);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    Mockito.verify(jdbc).query(
        sql.capture(),
        Mockito.<RowMapper<Object>>any(),
        eq(bucketMs), eq(bucketMs), eq("sys.cpu_pct"), eq(expectedCutoff.toString()));
    String sqlLc = sql.getValue().toLowerCase();
    assertTrue(sqlLc.contains("metric_sample"));
    assertTrue(sqlLc.contains("group by"));
    assertTrue(sqlLc.contains("order by"));
    assertTrue(sqlLc.contains("bucket_start_ms"));
  }

  @Test
  void bucketed24h_returns_empty_list_on_failure() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    Mockito.when(jdbc.query(anyString(), Mockito.<RowMapper<Object>>any(),
        any(), any(), any(), any()))
        .thenThrow(new RuntimeException("no such table"));
    MetricsRepo repo = new MetricsRepo(jdbc);
    assertEquals(List.of(), repo.bucketed24h("sys.cpu_pct", 5, Instant.now()));
  }
}
