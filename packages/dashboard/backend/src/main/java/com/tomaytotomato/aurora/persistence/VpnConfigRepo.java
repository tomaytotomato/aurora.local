package com.tomaytotomato.aurora.persistence;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * The singleton {@code vpn_config} row — Aurora's own inbound WireGuard
 * server settings. "Not configured" is modelled as "no row exists yet",
 * not a row full of nulls/zeros, so {@link #find()} returning empty is
 * the one true signal for the {@code not-configured} state.
 */
@Repository
public class VpnConfigRepo {

  private static final RowMapper<VpnConfigRow> MAPPER = (rs, i) -> new VpnConfigRow(
      rs.getString("endpoint_host"),
      rs.getInt("listen_port"),
      rs.getString("dns"),
      rs.getString("server_address"),
      rs.getInt("mtu"),
      rs.getString("server_private_key"),
      rs.getString("server_public_key")
  );

  private final JdbcTemplate jdbc;

  public VpnConfigRepo(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<VpnConfigRow> find() {
    try {
      return Optional.ofNullable(jdbc.queryForObject(
          "SELECT endpoint_host, listen_port, dns, server_address, mtu, "
              + "server_private_key, server_public_key FROM vpn_config WHERE id = 1",
          MAPPER));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  public boolean exists() {
    Long n = jdbc.queryForObject("SELECT COUNT(*) FROM vpn_config WHERE id = 1", Long.class);
    return n != null && n > 0;
  }

  /** First-run: create the singleton row. Callers guard the 409-if-exists rule. */
  public void insert(VpnConfigRow row) {
    jdbc.update(
        "INSERT INTO vpn_config (id, endpoint_host, listen_port, dns, server_address, mtu, "
            + "server_private_key, server_public_key) VALUES (1, ?, ?, ?, ?, ?, ?, ?)",
        row.endpointHost(), row.listenPort(), row.dns(), row.serverAddress(), row.mtu(),
        row.serverPrivateKey(), row.serverPublicKey());
  }

  /** Partial update of the editable fields — mirrors PUT /vpn/config. */
  public void update(String endpointHost, int listenPort, String dns, String serverAddress, int mtu) {
    jdbc.update(
        "UPDATE vpn_config SET endpoint_host = ?, listen_port = ?, dns = ?, server_address = ?, "
            + "mtu = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = 1",
        endpointHost, listenPort, dns, serverAddress, mtu);
  }

  /** Regenerate just the keypair — POST /vpn/server/rotate-key. */
  public void updateKeys(String serverPrivateKey, String serverPublicKey) {
    jdbc.update(
        "UPDATE vpn_config SET server_private_key = ?, server_public_key = ?, "
            + "updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = 1",
        serverPrivateKey, serverPublicKey);
  }
}
