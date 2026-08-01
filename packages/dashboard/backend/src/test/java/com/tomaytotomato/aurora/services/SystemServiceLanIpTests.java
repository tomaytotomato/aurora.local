package com.tomaytotomato.aurora.services;


import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure fib_trie parsing + scoring helpers. */
class SystemServiceLanIpTests {

  @Test
  void scores_192_168_highest() {
    assertEquals(100, SystemService.scoreLanCandidate("192.168.0.110"));
    assertEquals(100, SystemService.scoreLanCandidate("192.168.1.42"));
  }

  @Test
  void scores_10_x_but_excludes_proton_10_2() {
    assertEquals(50, SystemService.scoreLanCandidate("10.0.0.5"));
    assertEquals(50, SystemService.scoreLanCandidate("10.42.1.1"));
    assertEquals(0, SystemService.scoreLanCandidate("10.2.0.2"));
  }

  @Test
  void scores_172_16_range_but_excludes_docker_bridges() {
    assertEquals(20, SystemService.scoreLanCandidate("172.16.0.1"));
    assertEquals(20, SystemService.scoreLanCandidate("172.20.0.1"));
    assertEquals(0, SystemService.scoreLanCandidate("172.17.0.1"));
    assertEquals(0, SystemService.scoreLanCandidate("172.18.0.2"));
  }

  @Test
  void rejects_loopback_linklocal_multicast_cgnat() {
    assertEquals(0, SystemService.scoreLanCandidate("127.0.0.1"));
    assertEquals(0, SystemService.scoreLanCandidate("169.254.1.1"));
    assertEquals(0, SystemService.scoreLanCandidate("224.0.0.1"));
    assertEquals(0, SystemService.scoreLanCandidate("100.85.0.1")); // ProtonVPN CGNAT
    assertEquals(0, SystemService.scoreLanCandidate("0.0.0.0"));
  }

  @Test
  void rejects_malformed() {
    assertEquals(0, SystemService.scoreLanCandidate("not.an.ip"));
    assertEquals(0, SystemService.scoreLanCandidate("1.2.3"));
    assertEquals(0, SystemService.scoreLanCandidate("999.1.1.1"));
  }

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
      assertEquals("192.168.0.110", SystemService.pickBestLanIp(hostLocals));
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  @Test
  void pick_best_returns_null_when_only_rejected() {
    assertNull(SystemService.pickBestLanIp(List.of("127.0.0.1", "172.17.0.1", "100.85.0.1")));
  }
}
