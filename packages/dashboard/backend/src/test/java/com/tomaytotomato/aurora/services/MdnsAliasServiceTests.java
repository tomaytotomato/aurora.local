package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import com.tomaytotomato.aurora.services.MdnsAliasService.AliasView;
import com.tomaytotomato.aurora.services.MdnsAliasService.LabelSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MdnsAliasService} discovery + snapshot paths.
 *
 * <p>Deliberately hermetic — does NOT spawn any avahi-publish
 * subprocess. Instead exercises {@code discoverLabels}, {@code
 * isValidLabel}, and {@code aliases()} (a read-only snapshot) against
 * the fake-repo test fixture. Subprocess lifecycle is covered by
 * live smoke on Bruce's machine after deploy.
 *
 * <p>Fake-repo state (see src/test/resources/fake-repo/.state.yml):
 * {@code enabled: [core, media, notes]}, domain unset. That's fine
 * for the discovery tests; the {@code aliases()} test wires a
 * domain via a stubbed {@link StateFileService}.
 */
class MdnsAliasServiceTests {

  private StateFileService state;
  private SystemService system;
  private AuditEventRepo audit;
  private AuroraProperties props;
  private MdnsAliasService svc;

  @BeforeEach
  void setUp() {
    props = new AuroraProperties(
        "src/test/resources/fake-repo",
        "/proc",
        null,
        new AuroraProperties.Docker("unix:///var/run/docker.sock")
    );
    state = Mockito.mock(StateFileService.class);
    system = Mockito.mock(SystemService.class);
    audit = Mockito.mock(AuditEventRepo.class);
    svc = new MdnsAliasService(state, system, audit, props);
  }

  // ─── isValidLabel ────────────────────────────────────────────────────────

  @Test
  void isValidLabel_accepts_simple_hostnames() {
    assertThat(MdnsAliasService.isValidLabel("notes")).isTrue();
    assertThat(MdnsAliasService.isValidLabel("home-assistant")).isTrue();
    assertThat(MdnsAliasService.isValidLabel("a")).isTrue();
    assertThat(MdnsAliasService.isValidLabel("abc123")).isTrue();
  }

  @Test
  void isValidLabel_rejects_bogus_shapes() {
    assertThat(MdnsAliasService.isValidLabel("")).isFalse();
    assertThat(MdnsAliasService.isValidLabel(null)).isFalse();
    assertThat(MdnsAliasService.isValidLabel(" spaces ")).isFalse();
    // Leading hyphen — rejected by the [a-zA-Z0-9] anchor.
    assertThat(MdnsAliasService.isValidLabel("-lead")).isFalse();
    // Dots would flatten the FQDN — reject.
    assertThat(MdnsAliasService.isValidLabel("dot.ted")).isFalse();
    // >63 char labels are invalid per DNS.
    assertThat(MdnsAliasService.isValidLabel("a".repeat(64))).isFalse();
    // But 63 is the max legal.
    assertThat(MdnsAliasService.isValidLabel("a".repeat(63))).isTrue();
  }

  // ─── discoverLabels ──────────────────────────────────────────────────────

  @Test
  void discoverLabels_notes_prefers_manifest_over_caddy_snippet() {
    // fake-repo/packages/notes/manifest.yml declares vhosts: [notes, drafts].
    // The caddy.snippet ALSO declares a 'legacy' vhost; because the manifest
    // path runs first and putIfAbsent guards duplicates, 'notes' stays sourced
    // from manifest and 'legacy' does NOT appear (manifest-declared package
    // is trusted to be authoritative; caddy fallback only kicks in when the
    // manifest list is missing entirely).
    Map<String, LabelSource> labels = svc.discoverLabels("notes");

    assertThat(labels).containsOnlyKeys("notes", "drafts", "legacy");
    // notes + drafts sourced from manifest…
    assertThat(labels.get("notes").source()).isEqualTo("manifest");
    assertThat(labels.get("notes").pkg()).isEqualTo("notes");
    assertThat(labels.get("drafts").source()).isEqualTo("manifest");
    // …legacy sourced from caddy (unique to the snippet).
    assertThat(labels.get("legacy").source()).isEqualTo("caddy");
  }

  @Test
  void discoverLabels_media_falls_back_to_caddy_snippet() {
    // fake-repo/packages/media/manifest.yml has no vhosts field, so the
    // caddy.snippet grep pulls sonarr + radarr but SKIPS the commented-out
    // zombie line.
    Map<String, LabelSource> labels = svc.discoverLabels("media");

    assertThat(labels).containsOnlyKeys("sonarr", "radarr");
    assertThat(labels.get("sonarr").source()).isEqualTo("caddy");
    assertThat(labels.get("radarr").source()).isEqualTo("caddy");
    assertThat(labels.keySet()).doesNotContain("zombie");
  }

  @Test
  void discoverLabels_unknown_package_returns_empty() {
    // Package dir doesn't exist → empty map, no exception.
    assertThat(svc.discoverLabels("does-not-exist")).isEmpty();
  }

  @Test
  void discoverLabels_core_has_no_vhosts_and_no_snippet() {
    // fake-repo core has a bare manifest.yml with no vhosts + no caddy.snippet.
    // Discovery should be empty (safe default — no accidental aliases).
    assertThat(svc.discoverLabels("core")).isEmpty();
  }

  // ─── aliases() snapshot ──────────────────────────────────────────────────

  @Test
  void aliases_reflects_enabled_packages_with_labels_and_state_starting() {
    Mockito.when(state.readState()).thenReturn(new com.tomaytotomato.aurora.domain.RepoState(
        1, "aurora", "aurora.local", null,
        List.of("core", "media", "notes"),
        List.of()
    ));
    Mockito.when(system.lanIp()).thenReturn("192.168.0.110");

    List<AliasView> aliases = svc.aliases();

    // core contributes nothing; media contributes sonarr + radarr;
    // notes contributes notes + drafts + legacy. Order preserved
    // by enabled[] order + LinkedHashMap.
    assertThat(aliases).extracting(AliasView::alias).containsExactly(
        "sonarr.aurora.local",
        "radarr.aurora.local",
        "notes.aurora.local",
        "drafts.aurora.local",
        "legacy.aurora.local"
    );
    // All state=starting because no subprocess has been spawned.
    assertThat(aliases).allMatch(a -> "starting".equals(a.state()));
    // targetIp reflects SystemService.lanIp().
    assertThat(aliases).allMatch(a -> "192.168.0.110".equals(a.targetIp()));
    // labels line up with the alias-domain split.
    assertThat(aliases.get(0).label()).isEqualTo("sonarr");
    assertThat(aliases.get(0).pkg()).isEqualTo("media");
    assertThat(aliases.get(0).source()).isEqualTo("caddy");
    assertThat(aliases.get(2).pkg()).isEqualTo("notes");
    assertThat(aliases.get(2).source()).isEqualTo("manifest");
  }

  @Test
  void aliases_empty_when_domain_missing() {
    // Without a domain the alias FQDN would be malformed — skip entirely.
    Mockito.when(state.readState()).thenReturn(new com.tomaytotomato.aurora.domain.RepoState(
        1, "aurora", null, null,
        List.of("notes"), List.of()
    ));
    Mockito.when(system.lanIp()).thenReturn("192.168.0.110");

    assertThat(svc.aliases()).isEmpty();
  }

  @Test
  void aliases_empty_when_no_packages_enabled() {
    Mockito.when(state.readState()).thenReturn(new com.tomaytotomato.aurora.domain.RepoState(
        1, "aurora", "aurora.local", null, List.of(), List.of()
    ));
    Mockito.when(system.lanIp()).thenReturn("192.168.0.110");

    assertThat(svc.aliases()).isEmpty();
  }
}
