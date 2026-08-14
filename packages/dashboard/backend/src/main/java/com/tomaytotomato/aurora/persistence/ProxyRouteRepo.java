package com.tomaytotomato.aurora.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Hand-added reverse-proxy routes — the ones an operator created through
 * {@code POST /api/proxy/routes} rather than a package manifest.
 *
 * <p>Package-generated ("managed") routes never live here; {@link
 * com.tomaytotomato.aurora.services.ProxyService} derives those live from
 * each enabled package's {@code caddy.snippet}. This table exists purely
 * for the id/createdAt bookkeeping a file can't give cheaply — the actual
 * Caddy configuration for these routes lives in a rendered snippet file,
 * not in SQLite.
 */
@Repository
public class ProxyRouteRepo {

  private final JdbcTemplate jdbc;

  public ProxyRouteRepo(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record Row(String id, String subdomain, String target, String createdAt) {}

  private static final String COLUMNS = "id, subdomain, target, created_at";

  public List<Row> findAll() {
    return jdbc.query("SELECT " + COLUMNS + " FROM proxy_route ORDER BY created_at, id", ProxyRouteRepo::map);
  }

  public Optional<Row> findById(String id) {
    return jdbc.query("SELECT " + COLUMNS + " FROM proxy_route WHERE id = ?", ProxyRouteRepo::map, id)
        .stream().findFirst();
  }

  public Row insert(String id, String subdomain, String target) {
    jdbc.update("INSERT INTO proxy_route (id, subdomain, target) VALUES (?, ?, ?)", id, subdomain, target);
    return findById(id).orElseThrow();
  }

  /** @return true if a row was actually removed. */
  public boolean delete(String id) {
    return jdbc.update("DELETE FROM proxy_route WHERE id = ?", id) > 0;
  }

  private static Row map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    return new Row(rs.getString("id"), rs.getString("subdomain"), rs.getString("target"), rs.getString("created_at"));
  }
}
