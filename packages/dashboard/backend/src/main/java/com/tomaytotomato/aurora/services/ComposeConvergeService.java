package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Java-native converge: bring a resolved set of packages up with
 * {@code docker compose}, invoked directly rather than through
 * {@code scripts/up.sh}.
 *
 * <p>This is the first slice of moving package management out of bash and
 * into Aurora (see PLAN). The seam is deliberately narrow: this class owns
 * only the two things bash uniquely did — assembling the {@code -f} file
 * list (with the dashboard orphan guard and the self-launch service
 * exclusion) and running {@code docker compose up} — and reuses the Java
 * services that already exist for everything around it (dependency
 * resolution in {@code OnboardingService}, snippet render in
 * {@link CaddySnippetService}, state in {@link StateFileService}). The
 * caller passes an already-resolved, dependency-first package list.
 *
 * <p>The port carries {@code up.sh}'s hard-won behaviours forward:
 * <ul>
 *   <li>A package with a manifest but no {@code compose.yml} is skipped
 *       with a warning, not fatal.</li>
 *   <li>Compose files are ordered as given (dependency-first), so core is
 *       first and relative bind-mount paths resolve against it — the
 *       first-{@code -f}-file rule in docs/PACKAGE_CONTRACT.md.</li>
 *   <li><b>Dashboard orphan guard</b>: the dashboard's compose file is
 *       forced into the {@code -f} list whenever it is installed but not
 *       requested, so {@code --remove-orphans} cannot reap the running
 *       control-plane container.</li>
 *   <li><b>Self-launch guard</b>: when this converge runs from inside the
 *       dashboard's own container, or the dashboard was only forced in for
 *       the orphan guard, the dashboard's own service(s) are excluded from
 *       {@code up -d} so the container issuing the command is never
 *       recreated mid-flight.</li>
 *   <li><b>Pull policy</b>: {@code up --pull missing} instead of a blanket
 *       {@code docker compose pull}. The old unconditional pull would
 *       eventually swap a floating-tag core image mid-install and cause a
 *       real control-plane outage; {@code missing} only fetches images not
 *       already present.</li>
 * </ul>
 */
@Service
public class ComposeConvergeService {

  private static final Logger log = LoggerFactory.getLogger(ComposeConvergeService.class);

  /**
   * {@code up.sh} always runs {@code docker compose -p aurora ...}, so every
   * container is labelled with this project regardless of each compose
   * file's own {@code name:}. Keep the same project so the Java path
   * converges the very same set of containers rather than creating a
   * parallel project.
   */
  static final String PROJECT = "aurora";

  private final AuroraProperties props;
  private final CommandRunner commands;

  public ComposeConvergeService(AuroraProperties props, CommandRunner commands) {
    this.props = props;
    this.commands = commands;
  }

  /**
   * A fully-assembled {@code docker compose} invocation: the argv to run,
   * the extra environment to run it with, the working directory, and the
   * ordered compose files it references (exposed for logging/tests).
   */
  public record ComposePlan(
      List<String> argv,
      Map<String, String> env,
      Path workingDir,
      List<String> composeFiles) {}

  // ------------------------------------------------------------------
  // Planning (pure — no docker, no processes)
  // ------------------------------------------------------------------

  /**
   * The ordered {@code -f} compose files for a resolved package list.
   *
   * <p>Skips a package whose {@code compose.yml} is absent (a half-written
   * package, warned not fatal). Applies the dashboard orphan guard: if the
   * dashboard is installed (its {@code .env} exists) but not in the
   * requested set, its compose file is appended so {@code --remove-orphans}
   * cannot reap it.
   *
   * @return absolute compose-file paths, core-first, dependency order
   */
  List<Path> composeFiles(List<String> resolvedPkgs) {
    Path repo = Path.of(props.repoPath());
    List<Path> files = new ArrayList<>();
    boolean dashboardRequested = resolvedPkgs.contains("dashboard");

    for (String p : resolvedPkgs) {
      Path f = repo.resolve("packages").resolve(p).resolve("compose.yml");
      if (!Files.isRegularFile(f)) {
        log.warn("package '{}' has no compose.yml — skipping", p);
        continue;
      }
      files.add(f);
    }

    if (!dashboardRequested) {
      Path dashCompose = repo.resolve("packages/dashboard/compose.yml");
      Path dashEnv = repo.resolve("packages/dashboard/.env");
      if (Files.isRegularFile(dashCompose) && Files.isRegularFile(dashEnv)) {
        files.add(dashCompose);
        log.info("dashboard installed but not requested; forcing its compose "
            + "file in so --remove-orphans can't reap it");
      }
    }
    return files;
  }

  /** Whether the dashboard was forced in purely for the orphan guard. */
  boolean dashboardForced(List<String> resolvedPkgs, List<Path> files) {
    if (resolvedPkgs.contains("dashboard")) return false;
    Path dashCompose = Path.of(props.repoPath()).resolve("packages/dashboard/compose.yml");
    return files.contains(dashCompose);
  }

  /**
   * Compose profiles for this converge. Mirrors {@code up.sh}: the {@code cpu}
   * profile is added unless {@code gpu} was explicitly requested (the two
   * are mutually exclusive; both bind Ollama's :11434).
   */
  static List<String> effectiveProfiles(List<String> requested) {
    List<String> out = new ArrayList<>(requested);
    if (!out.contains("gpu") && !out.contains("cpu")) {
      out.add("cpu");
    }
    return out;
  }

  /**
   * Build the {@code docker compose ... up -d} argv.
   *
   * @param files            ordered {@code -f} compose files
   * @param upTargetServices when non-empty, {@code up -d} is scoped to
   *                         exactly these services (the self-launch guard
   *                         hands every service EXCEPT the dashboard's own);
   *                         when empty, {@code up -d} acts on the whole
   *                         merged project
   */
  static List<String> upArgv(List<Path> files, List<String> upTargetServices) {
    List<String> argv = new ArrayList<>();
    argv.add("docker");
    argv.add("compose");
    argv.add("-p");
    argv.add(PROJECT);
    for (Path f : files) {
      argv.add("-f");
      argv.add(f.toString());
    }
    argv.add("up");
    argv.add("-d");
    // Only fetch images not already present. NOT a blanket pull: a floating
    // tag (caddy:2-alpine, authelia:latest) pulled on every converge would
    // eventually swap a running core image mid-install.
    argv.add("--pull");
    argv.add("missing");
    argv.add("--remove-orphans");
    argv.addAll(upTargetServices);
    return argv;
  }

  /**
   * The {@code docker compose ... config --services} argv for one compose
   * file — used to learn the dashboard's own service names so the
   * self-launch guard can exclude them.
   */
  static List<String> configServicesArgv(Path composeFile) {
    return List.of("docker", "compose", "-f", composeFile.toString(), "config", "--services");
  }

  // ------------------------------------------------------------------
  // Running (touches docker)
  // ------------------------------------------------------------------

  /**
   * Ensure the shared external network exists. {@code docker network create}
   * is idempotent-by-check here: inspect first, create only if absent, so a
   * converge on an existing box is a no-op.
   */
  void ensureNetwork() {
    var inspect = commands.run(List.of("docker", "network", "inspect", "aurora_net"));
    if (inspect.ok()) return;
    var created = commands.run(List.of("docker", "network", "create", "aurora_net"));
    if (!created.ok()) {
      log.warn("could not create docker network aurora_net: {}", created.text());
    }
  }

  /**
   * The service names defined by a single compose file. Empty when the
   * lookup fails (the caller then simply excludes nothing, which is safe —
   * worst case the dashboard gets ordinary recreate semantics).
   */
  List<String> servicesOf(Path composeFile) {
    var r = commands.run(Path.of(props.repoPath()), configServicesArgv(composeFile));
    if (!r.ok()) return List.of();
    List<String> out = new ArrayList<>();
    for (String line : r.lines()) {
      String s = line.strip();
      if (!s.isEmpty()) out.add(s);
    }
    return out;
  }

  /**
   * Assemble the full converge plan for a resolved package set.
   *
   * @param resolvedPkgs dependency-first resolved package names
   * @param profiles     requested compose profiles (before the cpu default)
   * @param selfLaunch   true when this converge is issued from inside the
   *                     dashboard's own container; combined with the orphan
   *                     guard it decides whether the dashboard's services
   *                     are excluded from {@code up -d}
   */
  public ComposePlan plan(List<String> resolvedPkgs, List<String> profiles, boolean selfLaunch) {
    List<Path> files = composeFiles(resolvedPkgs);
    boolean forced = dashboardForced(resolvedPkgs, files);
    boolean requested = resolvedPkgs.contains("dashboard");

    // Exclude the dashboard's own services from `up -d` when it was only
    // forced in for the orphan guard, OR when a self-launched converge
    // genuinely includes the dashboard (don't recreate the container we run
    // in). A host operator bringing the dashboard up by hand hits neither.
    boolean restrict = forced || (selfLaunch && requested);
    List<String> upTargets = List.of();
    if (restrict) {
      Path dashCompose = Path.of(props.repoPath()).resolve("packages/dashboard/compose.yml");
      List<String> dashServices = servicesOf(dashCompose);
      List<String> all = mergedServices(files);
      List<String> targets = new ArrayList<>();
      for (String svc : all) {
        if (!dashServices.contains(svc)) targets.add(svc);
      }
      upTargets = targets;
    }

    Map<String, String> env = new LinkedHashMap<>();
    List<String> eff = effectiveProfiles(profiles);
    if (!eff.isEmpty()) {
      env.put("COMPOSE_PROFILES", String.join(",", eff));
    }

    List<String> fileStrings = files.stream().map(Path::toString).toList();
    return new ComposePlan(upArgv(files, upTargets), env, Path.of(props.repoPath()), fileStrings);
  }

  /** All service names across the merged compose file set. */
  List<String> mergedServices(List<Path> files) {
    List<String> argv = new ArrayList<>(List.of("docker", "compose", "-p", PROJECT));
    for (Path f : files) {
      argv.add("-f");
      argv.add(f.toString());
    }
    argv.add("config");
    argv.add("--services");
    var r = commands.run(Path.of(props.repoPath()), argv);
    if (!r.ok()) return List.of();
    List<String> out = new ArrayList<>();
    for (String line : r.lines()) {
      String s = line.strip();
      if (!s.isEmpty()) out.add(s);
    }
    return out;
  }

  /**
   * Run the converge, streaming {@code docker compose} output line by line.
   *
   * @return the {@code docker compose up} exit code
   */
  public int run(List<String> resolvedPkgs, List<String> profiles, boolean selfLaunch,
                 Consumer<String> onLine, CommandRunner.CancelToken cancelToken)
      throws IOException, InterruptedException {
    ensureNetwork();
    ComposePlan p = plan(resolvedPkgs, profiles, selfLaunch);
    if (p.composeFiles().isEmpty()) {
      onLine.accept("[aurora] no compose files to bring up");
      return 0;
    }
    return commands.stream(p.workingDir(), p.env(), p.argv(), onLine, cancelToken);
  }
}
