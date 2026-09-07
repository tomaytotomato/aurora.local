package com.tomaytotomato.aurora.security;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The always-installed packages must ship digest-pinned images.
 *
 * <p>Aurora's own Security page raises a MEDIUM finding for any running
 * container on a moving tag ({@link UnpinnedImageTagsRule}). The
 * 2026-08-30 homelabber review pointed out the obvious problem with
 * that: Aurora was simultaneously the source of the finding and the
 * cause of it, shipping {@code adguard/adguardhome:latest} in its own
 * catalogue.
 *
 * <p>Worse, {@code core} shipped {@code authelia/authelia:latest} — the
 * SSO provider for the whole box, and the exact component whose restart
 * loop was the review's headline outage. On 2026-09-07 {@code :latest}
 * had already advanced to a different digest than the one the testbed
 * had been running for five days, so a routine {@code docker compose
 * pull} would have swapped the identity provider for an unreviewed
 * image.
 *
 * <p>Scope is deliberately narrow: {@code core} (always installed,
 * carries the proxy + SSO + database) and {@code privacy} (AdGuard is
 * the box's DNS). The rest of the catalogue still carries moving tags
 * and pinning it is a curation job — each pin in
 * {@code pins.env.example} is human-reviewed, and thirty unreviewed
 * digests resolved by a script is not an improvement. Widening this
 * list is the tracked follow-up; until then this test holds the line
 * where it has actually been done rather than failing loudly about work
 * nobody has started.
 */
class CorePackagesArePinnedTests {

  /**
   * {@code image:} lines, capturing the reference. Skips commented-out
   * lines so the {@code _template} package's illustrative
   * {@code # image: myimage:latest} does not count.
   */
  private static final Pattern IMAGE_LINE =
      Pattern.compile("^\\s*image:\\s*(\\S+)\\s*$");

  /**
   * Compose interpolation with a default: {@code ${VAR:-fallback}}. The
   * fallback is what a fresh clone gets, because the override files
   * ({@code pins.env}) are gitignored and therefore absent until
   * someone runs {@code scripts/pin.sh --refresh}. Pinning only inside
   * pins.env would leave every fresh install on a moving tag, which is
   * precisely the case the review caught.
   */
  private static final Pattern INTERPOLATED_DEFAULT =
      Pattern.compile("^\\$\\{[A-Za-z_][A-Za-z0-9_]*:-(.+)}$");

  private static final List<String> ALWAYS_INSTALLED = List.of("core", "privacy");

  @TestFactory
  List<DynamicTest> always_installed_packages_pin_every_image_by_digest() {
    List<DynamicTest> tests = new ArrayList<>();
    for (String pkg : ALWAYS_INSTALLED) {
      tests.add(DynamicTest.dynamicTest(pkg + "/compose.yml", () -> {
        Path compose = Path.of("../../../packages", pkg, "compose.yml");
        // Only runs where the sibling packages/ tree is visible: local
        // mvn test, not the sandboxed backend-only container. Same
        // convention as AutheliaConfigurationInvariantsTests.
        assumeTrue(Files.exists(compose),
            "packages/" + pkg + "/compose.yml not visible from this sandbox");

        List<String> unpinned = unpinnedImagesIn(compose);
        assertThat(unpinned)
            .as("packages/%s/compose.yml must pin every image by digest — a moving "
                + "tag here means Aurora's own Security page flags the box Aurora "
                + "just built. Resolve a digest with: docker buildx imagetools "
                + "inspect --format '{{.Manifest.Digest}}' <ref>", pkg)
            .isEmpty();
      }));
    }
    return tests;
  }

  /**
   * Image references in the file that are not digest-pinned, resolving
   * {@code ${VAR:-default}} to the default first so the fresh-clone case
   * is what gets judged.
   */
  private static List<String> unpinnedImagesIn(Path compose) throws IOException {
    List<String> unpinned = new ArrayList<>();
    for (String line : Files.readString(compose, StandardCharsets.UTF_8).split("\\R")) {
      if (line.trim().startsWith("#")) continue;
      Matcher m = IMAGE_LINE.matcher(line);
      if (!m.matches()) continue;

      String ref = m.group(1);
      Matcher interpolated = INTERPOLATED_DEFAULT.matcher(ref);
      String effective = interpolated.matches() ? interpolated.group(1) : ref;

      if (UnpinnedImageTagsRule.classify(effective) != UnpinnedImageTagsRule.Verdict.PINNED) {
        unpinned.add(ref);
      }
    }
    return unpinned;
  }
}
