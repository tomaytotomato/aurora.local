package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.config.MarketplaceProperties;
import com.tomaytotomato.aurora.domain.MarketplaceApp;
import com.tomaytotomato.aurora.domain.MarketplaceIndex;
import com.tomaytotomato.aurora.domain.MarketplaceStatus;
import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import com.tomaytotomato.aurora.persistence.SettingsRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The marketplace catalogue's trust invariants, exercised against the
 * real seed index shipped in the build — which is signed by the dev
 * release key whose public half is pinned into the same build. So these
 * tests run the genuine Ed25519 verification path, not a stubbed one.
 *
 * <p>What is pinned here, in order of importance:
 * <ol>
 *   <li>A blob whose bytes were tampered with fails verification.</li>
 *   <li>The seed loads as the active catalogue on a box with no cache.</li>
 *   <li>A cached index (persisted by us, hence validly signed) is
 *       preferred over the seed, and a corrupted cache is ignored in
 *       favour of the seed rather than crashing.</li>
 *   <li>The summary projection carries no embedded compose/README; the
 *       detail projection does.</li>
 * </ol>
 */
class MarketplaceCatalogServiceTests {

  @TempDir
  Path repo;

  private MarketplaceCatalogService svc;
  private SettingsRepo settings;
  private AuditEventRepo audit;

  @BeforeEach
  void setUp() {
    AuroraProperties props = Mockito.mock(AuroraProperties.class);
    Mockito.when(props.repoPath()).thenReturn(repo.toString());
    MarketplaceProperties config = new MarketplaceProperties(false, null, null, false);
    settings = Mockito.mock(SettingsRepo.class);
    Mockito.when(settings.get(Mockito.anyString())).thenReturn(Optional.empty());
    audit = Mockito.mock(AuditEventRepo.class);
    svc = new MarketplaceCatalogService(props, config, settings, audit);
  }

  private byte[] seedBlob() throws Exception {
    try (InputStream in = getClass().getResourceAsStream("/marketplace/index.seed.json")) {
      return in.readAllBytes();
    }
  }

  private byte[] seedSig() throws Exception {
    try (InputStream in = getClass().getResourceAsStream("/marketplace/index.seed.json.sig")) {
      return in.readAllBytes();
    }
  }

  @Test
  void real_seed_signature_verifies_against_the_pinned_key() throws Exception {
    assertThat(svc.verify(seedBlob(), seedSig())).isTrue();
  }

  @Test
  void a_tampered_blob_does_not_verify() throws Exception {
    byte[] blob = seedBlob();
    blob[blob.length / 2] ^= 0x01; // flip one bit in the middle
    assertThat(svc.verify(blob, seedSig())).isFalse();
  }

  @Test
  void a_garbage_signature_does_not_verify() throws Exception {
    assertThat(svc.verify(seedBlob(), "not-base64-or-a-signature".getBytes(StandardCharsets.UTF_8)))
        .isFalse();
  }

  @Test
  void parses_the_seed_into_apps() throws Exception {
    MarketplaceIndex idx = svc.parse(seedBlob());
    assertThat(idx.schemaVersion()).isEqualTo(1);
    assertThat(idx.indexVersion()).startsWith("v");
    assertThat(idx.apps()).isNotEmpty();
    // Every app carries at least one image entry (schema minItems: 1).
    assertThat(idx.apps()).allSatisfy(a -> assertThat(a.images()).isNotEmpty());
  }

  @Test
  void loads_the_seed_as_active_when_no_cache_exists() {
    svc.loadActiveFromDisk();
    MarketplaceStatus status = svc.status();
    assertThat(status.source()).isEqualTo("seed");
    assertThat(status.signatureValid()).isTrue();
    assertThat(status.appCount()).isGreaterThan(0);
    assertThat(status.activeVersion()).startsWith("v");
    assertThat(status.updateAvailable()).isFalse();
  }

  @Test
  void prefers_a_valid_cache_over_the_seed() throws Exception {
    Files.createDirectories(svc.cacheDir());
    Files.write(svc.cacheIndexPath(), seedBlob());
    Files.write(svc.cacheSigPath(), seedSig());
    svc.loadActiveFromDisk();
    assertThat(svc.status().source()).isEqualTo("cache");
  }

  @Test
  void ignores_a_corrupted_cache_and_falls_back_to_the_seed() throws Exception {
    Files.createDirectories(svc.cacheDir());
    byte[] blob = seedBlob();
    blob[blob.length / 2] ^= 0x01;
    Files.write(svc.cacheIndexPath(), blob);   // signature no longer matches
    Files.write(svc.cacheSigPath(), seedSig());
    svc.loadActiveFromDisk();
    // A cache that fails verification must not become active; the seed does.
    assertThat(svc.status().source()).isEqualTo("seed");
    assertThat(svc.status().signatureValid()).isTrue();
  }

  @Test
  void summary_projection_omits_embedded_bodies_detail_keeps_them() {
    svc.loadActiveFromDisk();
    MarketplaceApp summary = svc.apps().get(0);
    assertThat(summary.compose()).isNull();
    assertThat(summary.readme()).isNull();

    Optional<MarketplaceApp> detail = svc.app(summary.slug());
    assertThat(detail).isPresent();
    // Real packages all ship a compose.yml, embedded on the detail path.
    assertThat(detail.get().compose()).isNotBlank();
  }

  @Test
  void disabled_status_reports_enabled_false() {
    svc.loadActiveFromDisk();
    assertThat(svc.status().enabled()).isFalse(); // config.enabled() == false
  }
}
