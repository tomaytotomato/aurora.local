package com.tomaytotomato.aurora.persistence;

import com.tomaytotomato.aurora.domain.OpenVpnClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class OpenVpnClientRepo {

  private static final RowMapper<OpenVpnClient> MAPPER = (rs, i) -> new OpenVpnClient(
      rs.getString("id"), rs.getString("name"), rs.getString("created_at"), rs.getString("last_connected_at"));

  private final JdbcTemplate jdbc;

  public OpenVpnClientRepo(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<OpenVpnClient> findAll() {
    return jdbc.query(
        "SELECT id, name, created_at, last_connected_at FROM vpn_openvpn_client ORDER BY created_at",
        MAPPER);
  }

  public void insert(OpenVpnClient client) {
    jdbc.update(
        "INSERT INTO vpn_openvpn_client (id, name, created_at, last_connected_at) VALUES (?, ?, ?, ?)",
        client.id(), client.name(), client.createdAt(), client.lastConnectedAt());
  }

  public int deleteById(String id) {
    return jdbc.update("DELETE FROM vpn_openvpn_client WHERE id = ?", id);
  }
}
