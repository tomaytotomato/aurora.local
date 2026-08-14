package com.tomaytotomato.aurora.persistence;

import com.tomaytotomato.aurora.domain.OpenVpnConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The singleton OpenVPN config row. Unlike WireGuard's {@code vpn_config},
 * this one is seeded with its defaults on first read rather than needing
 * an explicit init step — OpenVPN has no keypair to generate up front, so
 * there is no "not configured" state worth modelling for it (see
 * {@code GET /vpn/openvpn/config} having no 404 in the spec).
 */
@Repository
public class OpenVpnConfigRepo {

  private final JdbcTemplate jdbc;

  public OpenVpnConfigRepo(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public OpenVpnConfig find() {
    ensureRow();
    return jdbc.queryForObject(
        "SELECT enabled, port, protocol FROM vpn_openvpn_config WHERE id = 1",
        (rs, i) -> new OpenVpnConfig(rs.getInt("enabled") != 0, rs.getInt("port"), rs.getString("protocol")));
  }

  public void update(boolean enabled, int port, String protocol) {
    ensureRow();
    jdbc.update("UPDATE vpn_openvpn_config SET enabled = ?, port = ?, protocol = ? WHERE id = 1",
        enabled ? 1 : 0, port, protocol);
  }

  private void ensureRow() {
    jdbc.update("INSERT OR IGNORE INTO vpn_openvpn_config (id) VALUES (1)");
  }
}
