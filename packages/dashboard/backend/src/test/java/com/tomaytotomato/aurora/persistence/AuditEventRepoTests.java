package com.tomaytotomato.aurora.persistence;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class AuditEventRepoTests {

  @Test
  void query_no_filters_binds_only_limit() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    Mockito.when(jdbc.query(anyString(), Mockito.<RowMapper<Object>>any(), any(Object[].class)))
        .thenReturn(List.of());
    new AuditEventRepo(jdbc).query(null, null, null, null, 50);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    Mockito.verify(jdbc).query(sql.capture(), Mockito.<RowMapper<Object>>any(), args.capture());
    String s = sql.getValue().toLowerCase();
    assertTrue(s.contains("from audit_event"));
    assertTrue(s.contains("order by ts desc"));
    assertTrue(s.endsWith("limit ?"));
    // Only the limit binding.
    assertEquals(1, args.getValue().length);
    assertEquals(50, args.getValue()[0]);
  }

  @Test
  void query_with_action_prefix_binds_escaped_like_pattern() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    Mockito.when(jdbc.query(anyString(), Mockito.<RowMapper<Object>>any(), any(Object[].class)))
        .thenReturn(List.of());
    new AuditEventRepo(jdbc).query("security.", null, null, null, 25);

    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    Mockito.verify(jdbc).query(anyString(), Mockito.<RowMapper<Object>>any(), args.capture());
    assertEquals("security.%", args.getValue()[0]);
    assertEquals(25, args.getValue()[1]);
  }

  @Test
  void query_binds_user_id_and_time_range() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    Mockito.when(jdbc.query(anyString(), Mockito.<RowMapper<Object>>any(), any(Object[].class)))
        .thenReturn(List.of());
    Instant since = Instant.parse("2026-08-01T00:00:00Z");
    Instant until = Instant.parse("2026-08-03T00:00:00Z");
    new AuditEventRepo(jdbc).query(null, 42L, since, until, 10);

    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    Mockito.verify(jdbc).query(anyString(), Mockito.<RowMapper<Object>>any(), args.capture());
    Object[] bound = args.getValue();
    assertEquals(4, bound.length);
    assertEquals(42L, bound[0]);
    assertEquals(since.toString(), bound[1]);
    assertEquals(until.toString(), bound[2]);
    assertEquals(10, bound[3]);
  }

  @Test
  void query_clamps_limit_to_max() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    Mockito.when(jdbc.query(anyString(), Mockito.<RowMapper<Object>>any(), any(Object[].class)))
        .thenReturn(List.of());
    new AuditEventRepo(jdbc).query(null, null, null, null, 10_000);

    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    Mockito.verify(jdbc).query(anyString(), Mockito.<RowMapper<Object>>any(), args.capture());
    assertEquals(AuditEventRepo.MAX_LIMIT, args.getValue()[0]);
  }

  @Test
  void query_clamps_limit_to_at_least_one() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    Mockito.when(jdbc.query(anyString(), Mockito.<RowMapper<Object>>any(), any(Object[].class)))
        .thenReturn(List.of());
    new AuditEventRepo(jdbc).query(null, null, null, null, 0);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    Mockito.verify(jdbc).query(anyString(), Mockito.<RowMapper<Object>>any(), args.capture());
    assertEquals(1, args.getValue()[0]);
  }

  @Test
  void query_returns_empty_on_failure() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    Mockito.when(jdbc.query(anyString(), Mockito.<RowMapper<Object>>any(), any(Object[].class)))
        .thenThrow(new RuntimeException("no such table"));
    assertEquals(List.of(), new AuditEventRepo(jdbc).query(null, null, null, null, 10));
  }

  @Test
  void record_still_works_after_query_refactor() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    new AuditEventRepo(jdbc).record(1L, "security.dismiss", "finding:x", null);
    Mockito.verify(jdbc).update(anyString(), Mockito.eq(1L), Mockito.eq("security.dismiss"),
        Mockito.eq("finding:x"), Mockito.eq(null));
  }

  @Test
  void escapeLike_covers_backslash_percent_underscore() {
    assertEquals("security.", AuditEventRepo.escapeLike("security."));
    assertEquals("a\\%b", AuditEventRepo.escapeLike("a%b"));
    assertEquals("a\\_b", AuditEventRepo.escapeLike("a_b"));
  }
}
