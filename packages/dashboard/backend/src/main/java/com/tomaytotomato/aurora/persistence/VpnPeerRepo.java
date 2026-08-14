package com.tomaytotomato.aurora.persistence;

import com.tomaytotomato.aurora.domain.VpnPeer;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * WireGuard peers. Rows map straight onto {@link VpnPeer} — unlike
 * {@code vpn_config}, there is no separate "row with secrets" shape here,
 * because the table itself never holds a peer's private key (see the
 * V4 migration comment).
 */
@Repository
public class VpnPeerRepo {

  private static final RowMapper<VpnPeer> MAPPER = (rs, i) -> new VpnPeer(
      rs.getString("id"),
      rs.getString("name"),
      rs.getString("public_key"),
      rs.getString("allowed_ips"),
      rs.getInt("kill_switch") != 0,
      rs.getInt("enabled") != 0,
      rs.getString("last_handshake_at"),
      rs.getLong("rx_bytes"),
      rs.getLong("tx_bytes"),
      rs.getString("created_at")
  );

  private final JdbcTemplate jdbc;

  public VpnPeerRepo(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<VpnPeer> findAll() {
    return jdbc.query(
        "SELECT id, name, public_key, allowed_ips, kill_switch, enabled, "
            + "last_handshake_at, rx_bytes, tx_bytes, created_at FROM vpn_peer ORDER BY created_at",
        MAPPER);
  }

  public Optional<VpnPeer> findById(String id) {
    try {
      return Optional.ofNullable(jdbc.queryForObject(
          "SELECT id, name, public_key, allowed_ips, kill_switch, enabled, "
              + "last_handshake_at, rx_bytes, tx_bytes, created_at FROM vpn_peer WHERE id = ?",
          MAPPER, id));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  public long count() {
    Long n = jdbc.queryForObject("SELECT COUNT(*) FROM vpn_peer", Long.class);
    return n == null ? 0L : n;
  }

  public void insert(VpnPeer peer) {
    jdbc.update(
        "INSERT INTO vpn_peer (id, name, public_key, allowed_ips, kill_switch, enabled, "
            + "last_handshake_at, rx_bytes, tx_bytes, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        peer.id(), peer.name(), peer.publicKey(), peer.allowedIps(), peer.killSwitch() ? 1 : 0,
        peer.enabled() ? 1 : 0, peer.lastHandshakeAt(), peer.rxBytes(), peer.txBytes(), peer.createdAt());
  }

  public int deleteById(String id) {
    return jdbc.update("DELETE FROM vpn_peer WHERE id = ?", id);
  }

  public int setEnabled(String id, boolean enabled) {
    return jdbc.update("UPDATE vpn_peer SET enabled = ? WHERE id = ?", enabled ? 1 : 0, id);
  }

  /**
   * Merge in a live reading from {@code wg show <iface> dump} for one
   * peer. Called from the status/peers read paths, never from a mutation
   * — this is Aurora catching up its own record of a fact WireGuard
   * itself is the source of truth for.
   */
  public int updateLiveStats(String id, String lastHandshakeAt, long rxBytes, long txBytes) {
    return jdbc.update(
        "UPDATE vpn_peer SET last_handshake_at = ?, rx_bytes = ?, tx_bytes = ? WHERE id = ?",
        lastHandshakeAt, rxBytes, txBytes, id);
  }
}
