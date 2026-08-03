package com.tomaytotomato.aurora.persistence;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * B4-followup (iter-23): {@link SecurityDismissalRepo} SQL + safety
 * contract. Mockito-over-JdbcTemplate matches the rest of the persistence
 * suite (see MetricsRepoTests).
 */
class SecurityDismissalRepoTests {

  private static Instant nowUtc() { return Instant.parse("2026-08-03T09:00:00Z"); }

  @Test
  void dismiss_writes_upsert_row_with_expiry() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    SecurityDismissalRepo repo = new SecurityDismissalRepo(jdbc);
    Instant expires = nowUtc().plusSeconds(7 * 24 * 3600);

    repo.dismiss("weak_admin_password:bruce", expires, "will rotate friday");

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    Mockito.verify(jdbc).update(sql.capture(),
        eq("weak_admin_password:bruce"),
        Mockito.anyString(),               // dismissed_at (Instant.now().toString())
        eq(expires.toString()),
        eq("will rotate friday"));
    assertTrue(sql.getValue().startsWith("INSERT OR REPLACE INTO security_dismissal"),
        "SQL was: " + sql.getValue());
  }

  @Test
  void dismiss_permanent_stores_null_expiry() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    new SecurityDismissalRepo(jdbc).dismiss("id-x", null, null);
    Mockito.verify(jdbc).update(anyString(),
        eq("id-x"),
        Mockito.anyString(),
        eq(null),
        eq(null));
  }

  @Test
  void dismiss_no_op_on_blank_id() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    SecurityDismissalRepo repo = new SecurityDismissalRepo(jdbc);
    repo.dismiss(null, nowUtc(), null);
    repo.dismiss("", nowUtc(), null);
    repo.dismiss("   ", nowUtc(), null);
    Mockito.verifyNoInteractions(jdbc);
  }

  @Test
  void dismiss_swallows_jdbc_failures() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    Mockito.doThrow(new RuntimeException("db down"))
        .when(jdbc).update(anyString(), any(), any(), any(), any());
    new SecurityDismissalRepo(jdbc).dismiss("id", nowUtc(), null);
  }

  @Test
  void restore_returns_true_when_row_removed() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    Mockito.when(jdbc.update(eq("DELETE FROM security_dismissal WHERE finding_id = ?"), eq("id")))
        .thenReturn(1);
    assertTrue(new SecurityDismissalRepo(jdbc).restore("id"));
  }

  @Test
  void restore_returns_false_when_no_row() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    Mockito.when(jdbc.update(anyString(), anyString())).thenReturn(0);
    assertFalse(new SecurityDismissalRepo(jdbc).restore("ghost"));
  }

  @Test
  void restore_no_op_and_false_on_blank_id() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    SecurityDismissalRepo repo = new SecurityDismissalRepo(jdbc);
    assertFalse(repo.restore(null));
    assertFalse(repo.restore(""));
    assertFalse(repo.restore("   "));
    Mockito.verifyNoInteractions(jdbc);
  }

  @Test
  void activeDismissals_binds_now_to_expires_at_clause() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    Instant now = nowUtc();
    Mockito.when(jdbc.queryForList(anyString(), eq(String.class), eq(now.toString())))
        .thenReturn(List.of("weak_admin_password:bruce", "docker_socket_exposure:aurora-portainer"));
    Set<String> got = new SecurityDismissalRepo(jdbc).activeDismissals(now);
    assertEquals(Set.of("weak_admin_password:bruce", "docker_socket_exposure:aurora-portainer"), got);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    Mockito.verify(jdbc).queryForList(sql.capture(), eq(String.class), eq(now.toString()));
    String sqlText = sql.getValue().toLowerCase();
    assertTrue(sqlText.contains("expires_at is null"), "SQL was: " + sqlText);
    assertTrue(sqlText.contains("expires_at > ?"), "SQL was: " + sqlText);
  }

  @Test
  void activeDismissals_defaults_now_when_null() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    Mockito.when(jdbc.queryForList(anyString(), eq(String.class), anyString()))
        .thenReturn(List.of());
    Set<String> got = new SecurityDismissalRepo(jdbc).activeDismissals(null);
    assertEquals(Set.of(), got);
  }

  @Test
  void activeDismissals_returns_empty_on_failure() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    Mockito.when(jdbc.queryForList(anyString(), eq(String.class), anyString()))
        .thenThrow(new RuntimeException("locked"));
    assertEquals(Set.of(), new SecurityDismissalRepo(jdbc).activeDismissals(Instant.now()));
  }

  @Test
  void pruneExpired_deletes_rows_past_cutoff() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    Instant now = nowUtc();
    Mockito.when(jdbc.update(anyString(), eq(now.toString()))).thenReturn(3);
    assertEquals(3, new SecurityDismissalRepo(jdbc).pruneExpired(now));
  }

  @Test
  void pruneExpired_returns_zero_on_failure() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    Mockito.when(jdbc.update(anyString(), anyString()))
        .thenThrow(new RuntimeException("locked"));
    assertEquals(0, new SecurityDismissalRepo(jdbc).pruneExpired(Instant.now()));
  }

  @Test
  void listAll_returns_map_rows() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    Mockito.when(jdbc.query(anyString(), Mockito.<RowMapper<Object>>any()))
        .thenReturn(List.of());
    assertEquals(List.of(), new SecurityDismissalRepo(jdbc).listAll());
  }
}
