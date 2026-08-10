package com.tomaytotomato.aurora.services;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The one test whose job is to check an assumption rather than our logic.
 *
 * <p>{@link UpdatesService} decides "is there an update" by comparing the
 * digest of the local image against the digest the registry serves for
 * that tag. Every unit test of that comparison asserts against a stub
 * written from the same belief that produced the code, so if the belief is
 * wrong the tests stay green and the feature is silently broken. This is
 * the test that cannot be fooled that way.
 *
 * <p>What it pins, using a real registry and a real multi-arch push:
 *
 * <ol>
 *   <li>{@code buildx imagetools inspect --format '{{.Manifest.Digest}}'}
 *       prints a bare digest.</li>
 *   <li>That digest equals the {@code RepoDigests} entry of the same image
 *       after pulling it — which is the comparison the feature makes.</li>
 *   <li>{@code docker manifest inspect --verbose} does <em>not</em> agree,
 *       for a manifest list. That is why the implementation does not use
 *       it, and this assertion is here so nobody "simplifies" it back.</li>
 * </ol>
 *
 * <p>Tagged {@code registry} and excluded from the default {@code mvn test}
 * run: it needs Docker, buildx and a reachable registry, and it takes
 * seconds rather than milliseconds. CI runs it with
 * {@code -Dgroups=registry}.
 */
@Tag("registry")
@DisplayName("what docker actually reports about digests")
class UpdatesRegistryContractTest {

  private static final DockerImageName REGISTRY = DockerImageName.parse("registry:2");

  /** Multi-arch on purpose: single-arch would not have caught the real bug. */
  private static final String PLATFORMS = "linux/amd64,linux/arm64";

  private static GenericContainer<?> registry;
  private static String reference;
  private static Path context;

  private static final CommandRunner COMMANDS = new ProcessCommandRunner();

  @BeforeAll
  static void pushAMultiArchImage() throws IOException {
    assumeTrue(dockerUsable(), "needs a working docker CLI with buildx");

    registry = new GenericContainer<>(REGISTRY).withExposedPorts(5000);
    registry.start();
    reference = "localhost:" + registry.getMappedPort(5000) + "/aurora-probe:1";

    // A trivial image over a small public base. The base pull is the only
    // thing here that needs the outside world.
    context = Files.createTempDirectory("aurora-probe");
    Files.writeString(context.resolve("Dockerfile"),
        "FROM alpine:3.20\nLABEL org.aurora.probe=\"digest-contract\"\n", StandardCharsets.UTF_8);

    var built = COMMANDS.run(context, Duration.ofMinutes(5), Map.of(),
        List.of("docker", "buildx", "build", "--platform", PLATFORMS,
            "-t", reference, "--push", "."));
    assumeTrue(built.ok(), () -> "could not build and push a probe image: " + built.text());

    // Pull it back so the local image is one docker knows came from a
    // registry, which is the state a real Aurora box is in.
    var pulled = COMMANDS.run(null, Duration.ofMinutes(2), Map.of(),
        List.of("docker", "pull", reference));
    assumeTrue(pulled.ok(), () -> "could not pull the probe image back: " + pulled.text());
  }

  @AfterAll
  static void tidyUp() {
    if (reference != null) {
      COMMANDS.run(List.of("docker", "image", "rm", "-f", reference));
    }
    if (registry != null) {
      registry.stop();
    }
    if (context != null) {
      try (var walk = Files.walk(context)) {
        walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
          try {
            Files.deleteIfExists(p);
          } catch (IOException ignore) {
            // Temp directory; the OS will get it.
          }
        });
      } catch (IOException ignore) {
        // Same.
      }
    }
  }

  private static boolean dockerUsable() {
    return new ProcessCommandRunner().run(List.of("docker", "buildx", "version")).ok();
  }

  private static String remoteDigestViaImagetools() {
    var result = COMMANDS.run(null, Duration.ofMinutes(1), Map.of(),
        List.of("docker", "buildx", "imagetools", "inspect", "--format",
            "{{.Manifest.Digest}}", reference));
    assertThat(result.ok()).as("imagetools inspect failed: %s", result.text()).isTrue();
    return result.firstLine().trim();
  }

  private static String localDigestViaImageInspect() {
    var result = COMMANDS.run(null, Duration.ofMinutes(1), Map.of(),
        List.of("docker", "image", "inspect", reference,
            "--format", "{{index .RepoDigests 0}}"));
    assertThat(result.ok()).as("image inspect failed: %s", result.text()).isTrue();
    return result.firstLine().trim();
  }

  // ------------------------------------------------------------------

  @Test
  void imagetools_prints_a_bare_digest() {
    assertThat(remoteDigestViaImagetools())
        .as("the --format template is expected to yield just the digest")
        .matches("sha256:[0-9a-f]{64}");
  }

  @Test
  void the_registry_digest_and_the_local_repo_digest_are_the_same_thing() {
    // This is the assertion the whole feature rests on. If these two are
    // not comparable, "is there an update" cannot be answered this way at
    // all.
    String remote = remoteDigestViaImagetools();
    String local = UpdatesService.extractDigest(localDigestViaImageInspect());

    assertThat(local).isNotNull();
    assertThat(local)
        .as("a freshly pulled image must read as up to date, not as behind")
        .isEqualTo(remote);
  }

  @Test
  void manifest_inspect_verbose_would_have_given_the_wrong_answer() {
    // Kept as a documented trap. On a manifest list, --verbose returns the
    // per-platform manifests; the first digest in that output is a platform
    // manifest, not the list digest that RepoDigests holds. Comparing them
    // would report an update for every multi-arch image, forever.
    var verbose = COMMANDS.run(null, Duration.ofMinutes(1), Map.of(),
        List.of("docker", "manifest", "inspect", "--verbose", reference));
    assumeTrue(verbose.ok(), "docker manifest inspect unavailable");

    var firstDigest = java.util.regex.Pattern
        .compile("\"digest\"\\s*:\\s*\"(sha256:[0-9a-f]{64})\"")
        .matcher(verbose.text());
    assumeTrue(firstDigest.find(), "no digest field in manifest inspect output");

    assertThat(firstDigest.group(1))
        .as("if this ever starts matching, the simpler command became safe "
            + "and UpdatesService.remoteDigest could be reconsidered")
        .isNotEqualTo(remoteDigestViaImagetools());
  }
}
