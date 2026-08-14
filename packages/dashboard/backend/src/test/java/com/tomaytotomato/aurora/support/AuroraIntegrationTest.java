package com.tomaytotomato.aurora.support;

import com.tomaytotomato.aurora.TestDockerConfig;
import com.tomaytotomato.aurora.services.CommandRunner;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Base for the backend integration tests: full Spring context, real
 * SQLite, a real repository directory on disk, and fakes only at the
 * boundaries Aurora does not own.
 *
 * <p>Per {@code TESTING.md} this is the default shape for new backend
 * work, and it is deliberately different from the older controller tests,
 * which are standalone MockMvc with mocked collaborators. Those pass and
 * are left alone; retrofitting ninety files is not this piece of work.
 *
 * <p>What is faked and why:
 * <ul>
 *   <li><b>Docker</b> — {@link TestDockerConfig} already existed for this.
 *       Nothing should open {@code /var/run/docker.sock} in a test.</li>
 *   <li><b>Commands</b> — {@link FakeCommandRunner}. {@code smartctl},
 *       {@code snapraid} and {@code wg} are not in the test image and
 *       would need privileges if they were.</li>
 * </ul>
 *
 * <p>What is real: the database (in-memory SQLite with the production
 * schema), the HTTP layer including Spring Security, and the repository
 * tree. That last one matters most — nearly every domain reads or writes
 * files under {@code aurora.repo-path}, so tests operate on an actual
 * directory seeded from {@code src/test/resources/fake-repo} and can
 * assert on what ended up on disk.
 *
 * <p>The repo directory is created once per JVM rather than per class, so
 * the Spring context stays cacheable across test classes; it is wiped and
 * re-seeded before each test.
 */
@SpringBootTest
@Import({TestDockerConfig.class, AuroraIntegrationTest.FakeCommandConfig.class})
public abstract class AuroraIntegrationTest {

  /**
   * Shared repo root. A per-class {@code @TempDir} would change the value
   * of {@code aurora.repo-path} between classes, and since
   * {@code AuroraProperties} binds once at startup the cached context
   * would keep pointing at the first class's directory. One stable path
   * avoids that whole trap.
   */
  protected static final Path REPO_ROOT;

  static {
    try {
      REPO_ROOT = Files.createTempDirectory("aurora-it-repo");
      REPO_ROOT.toFile().deleteOnExit();
    } catch (IOException e) {
      throw new UncheckedIOException("could not create the test repo directory", e);
    }
  }

  private static final Path FIXTURE = Path.of("src/test/resources/fake-repo");

  @DynamicPropertySource
  static void auroraProperties(DynamicPropertyRegistry registry) {
    registry.add("aurora.repo-path", REPO_ROOT::toString);
  }

  /**
   * Built by hand rather than with {@code @AutoConfigureMockMvc}: Spring
   * Boot 4 moved that annotation into a separate module, and wiring it up
   * here is one line and says plainly that the security filter chain is
   * part of what these tests exercise.
   */
  protected MockMvc mvc;

  @Autowired
  protected WebApplicationContext webContext;

  @Autowired
  protected FakeCommandRunner commands;

  @Autowired
  protected JdbcTemplate jdbcTemplate;

  /**
   * Tables a test can write to, cleared before each one.
   *
   * <p>The in-memory database is shared for the whole JVM so the Spring
   * context stays cacheable, which means without this a row written by one
   * test is visible to the next. That is exactly the sort of
   * ordering-dependent failure that passes alone and fails in the suite,
   * and it did: an override written by one resources test made the next
   * one report a ceiling nobody had set.
   *
   * <p>Subclasses that need seed data create it in their own
   * {@code @BeforeEach}, which JUnit runs after this one.
   */
  private static final List<String> MUTABLE_TABLES =
      List.of("settings", "audit_event", "security_dismissal", "metric_sample", "admin_user",
          "proxy_route", "notification_channel", "notification_delivery");

  @BeforeEach
  void resetTestWorld() throws IOException {
    mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
    commands.reset();

    for (String table : MUTABLE_TABLES) {
      try {
        jdbcTemplate.update("DELETE FROM " + table);
      } catch (DataAccessException e) {
        // A table that does not exist yet is not a reason to fail every
        // test; the ones that need it will say so loudly enough.
        LoggerFactory.getLogger(AuroraIntegrationTest.class)
            .debug("could not clear {}: {}", table, e.getMessage());
      }
    }

    wipe(REPO_ROOT);
    if (Files.isDirectory(FIXTURE)) {
      copyTree(FIXTURE, REPO_ROOT);
    }
  }

  // ------------------------------------------------------------------
  // Repository helpers
  //
  // Tests that care about file-driven behaviour write the file they care
  // about and leave the rest of the seeded tree alone.
  // ------------------------------------------------------------------

  /** Absolute path inside the test repository. */
  protected Path repoFile(String relative) {
    return REPO_ROOT.resolve(relative);
  }

  /** Write a file inside the test repository, creating parent directories. */
  protected Path writeRepoFile(String relative, String content) throws IOException {
    Path target = repoFile(relative);
    Files.createDirectories(target.getParent());
    Files.writeString(target, content, StandardCharsets.UTF_8);
    return target;
  }

  protected String readRepoFile(String relative) throws IOException {
    return Files.readString(repoFile(relative), StandardCharsets.UTF_8);
  }

  protected boolean repoFileExists(String relative) {
    return Files.exists(repoFile(relative));
  }

  /** Remove a seeded file, for the "what happens when it is missing" cases. */
  protected void deleteRepoFile(String relative) throws IOException {
    Files.deleteIfExists(repoFile(relative));
  }

  // ------------------------------------------------------------------
  // Plumbing
  // ------------------------------------------------------------------

  @TestConfiguration
  public static class FakeCommandConfig {
    @Bean
    @Primary
    public CommandRunner fakeCommandRunner() {
      return new FakeCommandRunner();
    }

    /**
     * Exposed under its concrete type as well so tests can inject the fake
     * for stubbing and assertions without casting the interface.
     */
    @Bean
    public FakeCommandRunner fakeCommandRunnerHandle(CommandRunner runner) {
      return (FakeCommandRunner) runner;
    }
  }

  private static void wipe(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) return;
    try (Stream<Path> children = Files.list(dir)) {
      for (Path child : children.toList()) {
        deleteRecursively(child);
      }
    }
  }

  private static void deleteRecursively(Path path) throws IOException {
    if (!Files.exists(path)) return;
    try (Stream<Path> walk = Files.walk(path)) {
      for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }

  private static void copyTree(Path from, Path to) throws IOException {
    Files.walkFileTree(from, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
          throws IOException {
        Files.createDirectories(to.resolve(from.relativize(dir).toString()));
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.copy(file, to.resolve(from.relativize(file).toString()));
        return FileVisitResult.CONTINUE;
      }
    });
  }
}
