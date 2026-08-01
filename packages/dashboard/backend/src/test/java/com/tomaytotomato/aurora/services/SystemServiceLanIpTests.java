package com.tomaytotomato.aurora.services;


import com.tomaytotomato.aurora.config.AuroraProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure fib_trie parsing + scoring helpers. */
class SystemServiceLanIpTests {

  private static SystemService newService(List<String> excludedCidrs) {
    AuroraProperties props = new AuroraProperties(
        "/repo",
        "/host/proc",
        excludedCidrs,
        new AuroraProperties.Docker("unix:///var/run/docker.sock"));
    return new SystemService(props, null);
  }

  private static SystemService defaultService() {
    // null -> record canonical constructor supplies default exclusions
    // (10.2.0.0/16, 172.17.0.0/16, 172.18.0.0/16).
    return newService(null);
  }

  // ------------------------------------------------------------------
  // Pure static scoring (universal rules only)
  // ------------------------------------------------------------------

  @Test
  void scores_192_168_highest() {
    assertEquals(100, SystemService.scoreLanCandidatePure("192.168.0.110"));
    assertEquals(100, SystemService.scoreLanCandidatePure("192.168.1.42"));
  }

  @Test
  void scores_10_x() {
    assertEquals(50, SystemService.scoreLanCandidatePure("10.0.0.5"));
    assertEquals(50, SystemService.scoreLanCandidatePure("10.42.1.1"));
    // Pure rules do NOT reject 10.2.x - that's a configurable exclusion.
    assertEquals(50, SystemService.scoreLanCandidatePure("10.2.0.2"));
  }

  @Test
  void scores_172_16_range() {
    assertEquals(20, SystemService.scoreLanCandidatePure("172.16.0.1"));
    assertEquals(20, SystemService.scoreLanCandidatePure("172.20.0.1"));
    // Pure rules do NOT reject docker bridges - configurable exclusion.
    assertEquals(20, SystemService.scoreLanCandidatePure("172.17.0.1"));
    assertEquals(20, SystemService.scoreLanCandidatePure("172.18.0.2"));
  }

  @Test
  void rejects_loopback_linklocal_multicast_cgnat() {
    assertEquals(0, SystemService.scoreLanCandidatePure("127.0.0.1"));
    assertEquals(0, SystemService.scoreLanCandidatePure("169.254.1.1"));
    assertEquals(0, SystemService.scoreLanCandidatePure("224.0.0.1"));
    assertEquals(0, SystemService.scoreLanCandidatePure("100.85.0.1")); // ProtonVPN CGNAT
    assertEquals(0, SystemService.scoreLanCandidatePure("0.0.0.0"));
  }

  @Test
  void rejects_malformed() {
    assertEquals(0, SystemService.scoreLanCandidatePure("not.an.ip"));
    assertEquals(0, SystemService.scoreLanCandidatePure("1.2.3"));
    assertEquals(0, SystemService.scoreLanCandidatePure("999.1.1.1"));
  }

  // ------------------------------------------------------------------
  // Instance-scoped scoring (applies configurable exclusions)
  // ------------------------------------------------------------------

  @Test
  void default_exclusions_reject_proton_and_docker_bridges() {
    SystemService svc = defaultService();
    // Defaults: 10.2.0.0/16, 172.17.0.0/16, 172.18.0.0/16.
    assertEquals(0, svc.scoreLanCandidate("10.2.0.2"));
    assertEquals(0, svc.scoreLanCandidate("172.17.0.1"));
    assertEquals(0, svc.scoreLanCandidate("172.18.0.2"));
    // Non-excluded private IPs still score.
    assertEquals(100, svc.scoreLanCandidate("192.168.0.110"));
    assertEquals(50, svc.scoreLanCandidate("10.0.0.5"));
    assertEquals(20, svc.scoreLanCandidate("172.20.0.1"));
  }

  @Test
  void configurable_exclusion_rejects_matching_candidate() {
    // Operator wants to exclude their own 192.168.99.0/24 (guest wifi, say).
    SystemService svc = newService(List.of("192.168.99.0/24"));
    assertEquals(0, svc.scoreLanCandidate("192.168.99.10"));
    // 192.168.0.x still fine because it's not in the excluded range.
    assertEquals(100, svc.scoreLanCandidate("192.168.0.110"));
    // Custom exclusions replace defaults, so 10.2.x is no longer rejected.
    assertEquals(50, svc.scoreLanCandidate("10.2.0.2"));
  }

  @Test
  void empty_exclusion_list_allows_everything_private() {
    SystemService svc = newService(List.of());
    assertEquals(50, svc.scoreLanCandidate("10.2.0.2"));
    assertEquals(20, svc.scoreLanCandidate("172.17.0.1"));
  }

  // ------------------------------------------------------------------
  // inCidr helper
  // ------------------------------------------------------------------

  @Test
  void inCidr_matches_16_prefix() {
    assertTrue(SystemService.inCidr("192.168.0.5", "192.168.0.0/16"));
    assertTrue(SystemService.inCidr("192.168.255.255", "192.168.0.0/16"));
    assertFalse(SystemService.inCidr("192.169.0.5", "192.168.0.0/16"));
  }

  @Test
  void inCidr_matches_32_boundary() {
    assertTrue(SystemService.inCidr("10.0.0.1", "10.0.0.1/32"));
    assertFalse(SystemService.inCidr("10.0.0.2", "10.0.0.1/32"));
  }

  @Test
  void inCidr_matches_0_prefix_universal() {
    assertTrue(SystemService.inCidr("1.2.3.4", "0.0.0.0/0"));
  }

  @Test
  void inCidr_returns_false_on_malformed() {
    assertFalse(SystemService.inCidr("192.168.0.5", "not a cidr"));
    assertFalse(SystemService.inCidr("192.168.0.5", "192.168.0.0"));      // no slash
    assertFalse(SystemService.inCidr("192.168.0.5", "192.168.0.0/33"));   // bad prefix
    assertFalse(SystemService.inCidr("192.168.0.5", "192.168.0.0/-1"));
    assertFalse(SystemService.inCidr("not.an.ip", "192.168.0.0/16"));
    assertFalse(SystemService.inCidr(null, "192.168.0.0/16"));
    assertFalse(SystemService.inCidr("192.168.0.5", null));
  }

  @Test
  void inCidr_docker_bridge_default() {
    assertTrue(SystemService.inCidr("172.17.0.1", "172.17.0.0/16"));
    assertTrue(SystemService.inCidr("172.18.0.2", "172.18.0.0/16"));
    assertFalse(SystemService.inCidr("172.19.0.1", "172.17.0.0/16"));
  }

  // ------------------------------------------------------------------
  // fib_trie parser
  // ------------------------------------------------------------------

  @Test
  void parses_fib_trie_sample_and_picks_192_168() throws Exception {
    // Sample mirroring the real host's /proc/1/net/fib_trie on aurora.
    String sample = String.join("\n",
        "Main:",
        "  +-- 0.0.0.0/0 3 0 5",
        "     |-- 0.0.0.0",
        "        /0 universe UNICAST",
        "     +-- 10.2.0.0/16 2 0 2",
        "        |-- 10.2.0.2",
        "           /32 host LOCAL",
        "     +-- 100.85.0.0/24 2 0 2",
        "        |-- 100.85.0.1",
        "           /32 host LOCAL",
        "     +-- 127.0.0.0/8 2 0 2",
        "           |-- 127.0.0.0",
        "              /8 host LOCAL",
        "           |-- 127.0.0.1",
        "              /32 host LOCAL",
        "     +-- 172.17.0.0/16 2 0 2",
        "        |-- 172.17.0.1",
        "           /32 host LOCAL",
        "     +-- 172.18.0.0/16 2 0 2",
        "        |-- 172.18.0.1",
        "           /32 host LOCAL",
        "     +-- 192.168.0.0/24 2 0 2",
        "        |-- 192.168.0.110",
        "           /32 host LOCAL",
        "");
    Path tmp = Files.createTempFile("fib_trie", ".txt");
    Files.writeString(tmp, sample);
    try {
      List<String> hostLocals = SystemService.parseFibTrieHostLocals(tmp);
      assertTrue(hostLocals.contains("192.168.0.110"), "expected 192.168.0.110 in: " + hostLocals);
      assertTrue(hostLocals.contains("172.17.0.1"));
      assertTrue(hostLocals.contains("100.85.0.1"));
      assertEquals("192.168.0.110", defaultService().pickBestLanIp(hostLocals));
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  @Test
  void pick_best_returns_null_when_only_rejected() {
    // 127.0.0.1 rejected by universal rules; 172.17/172.18 by default exclusions.
    assertNull(defaultService().pickBestLanIp(List.of("127.0.0.1", "172.17.0.1", "100.85.0.1")));
  }

  @Test
  void parser_resets_prev_ip_on_subtree_boundary() throws Exception {
    // Truncated / malformed fib_trie where a subtree declares an IP but never
    // reaches its `host LOCAL` line, then the next sibling subtree opens and
    // contains a stray `host LOCAL` with no preceding `|-- <ip>`. Without the
    // reset guard, the parser would misattribute the stray LOCAL to the
    // previous subtree's IP.
    String malformed = String.join("\n",
        "Main:",
        "  +-- 0.0.0.0/0 3 0 5",
        "     +-- 10.9.9.0/24 2 0 2",
        "        |-- 10.9.9.9",
        "           /32 universe UNICAST",   // no host LOCAL for 10.9.9.9
        "     +-- 192.168.0.0/24 2 0 2",
        "        /32 host LOCAL",             // stray - no preceding |-- <ip>
        "");
    Path tmp = Files.createTempFile("fib_trie_malformed", ".txt");
    Files.writeString(tmp, malformed);
    try {
      List<String> hostLocals = SystemService.parseFibTrieHostLocals(tmp);
      assertFalse(hostLocals.contains("10.9.9.9"),
          "subtree boundary must reset prevIp; got " + hostLocals);
      assertTrue(hostLocals.isEmpty(),
          "stray host LOCAL with no preceding |-- <ip> should attribute to no IP; got " + hostLocals);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}
