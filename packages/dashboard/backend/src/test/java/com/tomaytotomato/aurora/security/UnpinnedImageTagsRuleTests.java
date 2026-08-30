package com.tomaytotomato.aurora.security;

import com.github.dockerjava.api.model.Container;
import com.tomaytotomato.aurora.domain.SecurityFinding;
import com.tomaytotomato.aurora.security.UnpinnedImageTagsRule.Verdict;
import com.tomaytotomato.aurora.services.DockerService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnpinnedImageTagsRuleTests {

  private static Container container(String name, String image) {
    Container c = Mockito.mock(Container.class);
    Mockito.when(c.getNames()).thenReturn(new String[] { "/" + name });
    Mockito.when(c.getImage()).thenReturn(image);
    return c;
  }

  private static DockerService dockerWith(List<Container> containers) {
    DockerService d = Mockito.mock(DockerService.class);
    Mockito.when(d.listProjectContainers()).thenReturn(containers);
    return d;
  }

  // -- classify() --------------------------------------------------------

  @Test
  void classify_pins_digest_forms() {
    assertEquals(Verdict.PINNED,
        UnpinnedImageTagsRule.classify("postgres:16@sha256:abcd1234"));
    assertEquals(Verdict.PINNED,
        UnpinnedImageTagsRule.classify("postgres@sha256:abcd1234"));
    // Registry path shouldn't confuse the check.
    assertEquals(Verdict.PINNED,
        UnpinnedImageTagsRule.classify("ghcr.io/linuxserver/sonarr:v4@sha256:aaa"));
  }

  @Test
  void classify_latest_tag_and_no_tag() {
    assertEquals(Verdict.LATEST_TAG, UnpinnedImageTagsRule.classify("nginx:latest"));
    assertEquals(Verdict.LATEST_TAG, UnpinnedImageTagsRule.classify("nginx:LATEST"));
    // Bare name = implicit :latest.
    assertEquals(Verdict.LATEST_TAG, UnpinnedImageTagsRule.classify("nginx"));
    assertEquals(Verdict.LATEST_TAG, UnpinnedImageTagsRule.classify("linuxserver/sonarr"));
  }

  @Test
  void classify_floating_tag() {
    assertEquals(Verdict.FLOATING_TAG, UnpinnedImageTagsRule.classify("postgres:16"));
    assertEquals(Verdict.FLOATING_TAG, UnpinnedImageTagsRule.classify("nginx:1.25"));
    // Registry with port + explicit tag, no digest.
    assertEquals(Verdict.FLOATING_TAG,
        UnpinnedImageTagsRule.classify("registry.example.com:5000/foo:2024-01"));
  }

  @Test
  void classify_null_or_empty() {
    assertEquals(Verdict.PINNED, UnpinnedImageTagsRule.classify(null));
    // Empty string → no colon → tag "" → treated as latest.
    assertEquals(Verdict.LATEST_TAG, UnpinnedImageTagsRule.classify(""));
  }

  // -- rule integration --------------------------------------------------

  @Test
  void aurora_container_is_exempt() {
    Container aurora = container("aurora", "ghcr.io/tomaytotomato/aurora:main");
    assertEquals(List.of(),
        new UnpinnedImageTagsRule(dockerWith(List.of(aurora))).evaluate());
  }

  @Test
  void latest_tag_flagged_MEDIUM() {
    Container sonarr = container("aurora-media-sonarr", "linuxserver/sonarr:latest");
    var got = new UnpinnedImageTagsRule(dockerWith(List.of(sonarr))).evaluate();
    assertEquals(1, got.size());
    assertEquals(SecurityFinding.MEDIUM, got.get(0).severity());
    assertEquals("unpinned_image_tags:aurora-media-sonarr", got.get(0).id());
    assertTrue(got.get(0).description().contains(":latest"),
        "description should call out the :latest tag: " + got.get(0).description());
  }

  @Test
  void floating_tag_flagged_LOW() {
    Container pg = container("aurora-docs-postgres", "postgres:16");
    var got = new UnpinnedImageTagsRule(dockerWith(List.of(pg))).evaluate();
    assertEquals(1, got.size());
    // Aurora ships this compose file: the box was born in this state and
    // there is nothing the owner can do about it. That is not HIGH, and it
    // is not MEDIUM either.
    assertEquals(SecurityFinding.LOW, got.get(0).severity());
    assertTrue(got.get(0).description().contains("tag that can move"),
        "description should say what it means in plain words: " + got.get(0).description());
  }

  @Test
  void copy_is_addressed_to_the_owner_not_to_an_engineer() {
    Container sonarr = container("aurora-media-sonarr", "linuxserver/sonarr:latest");
    var f = new UnpinnedImageTagsRule(dockerWith(List.of(sonarr))).evaluate().get(0);
    assertTrue(f.title().startsWith("Aurora media sonarr"), "title: " + f.title());
    assertFalse(f.description().contains("@sha256"),
        "must not tell a non-technical owner to pin a digest: " + f.description());
    assertTrue(f.description().contains("nothing for you to do"),
        "must say who owns the fix: " + f.description());
  }

  @Test
  void digest_pinned_container_yields_no_finding() {
    Container ok = container("aurora-storage",
        "docker.io/library/nginx:1.25@sha256:abc123");
    assertEquals(List.of(),
        new UnpinnedImageTagsRule(dockerWith(List.of(ok))).evaluate());
  }

  @Test
  void mixed_workload_produces_finding_per_bad_container() {
    Container ok = container("aurora-notes", "silverbullet@sha256:aaa");
    Container latest = container("aurora-homepage", "gethomepage/homepage:latest");
    Container floating = container("aurora-photos-immich", "ghcr.io/immich/server:v1.100");
    var got = new UnpinnedImageTagsRule(
        dockerWith(List.of(ok, latest, floating))).evaluate();
    assertEquals(2, got.size());
    // Order is source order; no aggregation.
    assertEquals("unpinned_image_tags:aurora-homepage", got.get(0).id());
    assertEquals(SecurityFinding.MEDIUM, got.get(0).severity());
    assertEquals("unpinned_image_tags:aurora-photos-immich", got.get(1).id());
    assertEquals(SecurityFinding.LOW, got.get(1).severity());
  }

  @Test
  void rule_swallows_docker_exceptions() {
    DockerService d = Mockito.mock(DockerService.class);
    Mockito.when(d.listProjectContainers()).thenThrow(new RuntimeException("socket refused"));
    assertEquals(List.of(), new UnpinnedImageTagsRule(d).evaluate());
  }

  @Test
  void copy_avoids_shell_substrings() {
    Container bad = container("aurora-media-sonarr", "linuxserver/sonarr:latest");
    var got = new UnpinnedImageTagsRule(dockerWith(List.of(bad))).evaluate();
    String all = (got.get(0).title() + " " + got.get(0).description()).toLowerCase();
    assertTrue(!all.contains("sudo ") && !all.contains("./scripts/")
        && !all.contains("bash "), "copy must be user-facing, was: " + all);
  }
}
