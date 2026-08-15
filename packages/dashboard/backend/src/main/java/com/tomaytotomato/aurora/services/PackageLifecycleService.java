package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.domain.Package;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The real backend for the app-detail control panel's four verbs —
 * Install, Start, Disable, Uninstall — none of which had a working
 * endpoint before this class existed. {@code openapi.yaml} documented
 * {@code POST /packages/{name}/enable} and {@code .../disable} since the
 * frontend was written, but nobody had implemented either; {@code Start}
 * already existed at {@code POST /services/{name}/start} (see
 * {@link com.tomaytotomato.aurora.controllers.ServicesController}), and
 * this class supplies the other three.
 *
 * <h2>The vocabulary</h2>
 *
 * <p>Two independent axes, not four ad-hoc verbs:
 * <ul>
 *   <li><b>Enrolment</b> — is this package in {@code .state.yml}'s
 *       {@code enabled[]} at all? That is what "installed" means here.</li>
 *   <li><b>Running</b> — does it have a live container right now?</li>
 * </ul>
 *
 * <p>Which gives a small, honest state machine per non-core package:
 * <pre>
 *   NOT_INSTALLED  --install()-->  RUNNING
 *   RUNNING        --stop()-->     STOPPED   (enrolled, no container)
 *   STOPPED        --start()-->    RUNNING   (ServicesController, unchanged)
 *   STOPPED/RUNNING --uninstall()--> NOT_INSTALLED
 * </pre>
 *
 * <p><b>Disable vs. Uninstall</b> — the two words the UI needed and the
 * old two-verb backend didn't distinguish:
 * <ul>
 *   <li>{@link #stop} (Disable) tears the containers down
 *       ({@code scripts/down.sh <name>}, volumes preserved) but leaves the
 *       package in {@code enabled[]}. It is reversible with a plain
 *       {@code Start} — no reinstall, no re-onboarding.</li>
 *   <li>{@link #uninstall} (Uninstall) does the same teardown and then
 *       removes the package from {@code enabled[]}. It still does
 *       <b>not</b> touch {@code data/<name>} — {@code down.sh} was never
 *       asked to, and this class never calls anything else that would.
 *       A photo library an operator forgot to back up must not
 *       disappear because they clicked one confirm dialog; the frontend's
 *       own uninstall copy already promises this ("Its data stays on disk
 *       unless you also clear its volumes by hand").</li>
 * </ul>
 *
 * <p><b>Enable</b> (Install) is the mirror of Uninstall: add to
 * {@code enabled[]} and start. Rather than writing {@code .state.yml}
 * directly, it passes the caller's full desired enabled set to
 * {@code scripts/up.sh}, which writes it at the end of a successful run.
 * That matters: {@code up.sh} <em>overwrites</em> {@code enabled[]} with
 * whatever package list it was given, so a caller that passed only the
 * one package being installed would silently un-enrol every other
 * package on the box. Every argv this class builds for {@code up.sh}
 * is therefore the full existing set plus the one addition — never a
 * partial list.
 *
 * <p>{@code down.sh}, used by both {@link #stop} and {@link #uninstall},
 * has no such trap: given an explicit package name it tears down only
 * that package's containers and never touches {@code .state.yml}, so
 * {@link #uninstall} updates the enrolment list itself, once the
 * teardown has actually succeeded.
 *
 * <h2>Core packages</h2>
 *
 * <p>{@code core}, {@code identity} and {@code storage} refuse all four
 * verbs, enforced here rather than left to the frontend hiding buttons —
 * {@link com.tomaytotomato.aurora.controllers.ServicesController} and the
 * rest of the backend previously only special-cased {@code "core"}
 * (e.g. {@code OnboardingService}'s dependency injection), so
 * {@code identity}/{@code storage} were stoppable by anyone who could
 * reach the API directly. {@link #CORE_PACKAGES} is the one place this
 * list lives server-side; it mirrors the frontend's
 * {@code CORE_PACKAGES} in {@code api/packages.ts} exactly.
 */
@Service
public class PackageLifecycleService {

  /**
   * The platform baseline: the reverse proxy + dashboard, the auth
   * provider fronting sign-on for everything, and LAN file sharing. None
   * of the four lifecycle verbs apply to any of them — see class javadoc.
   */
  public static final Set<String> CORE_PACKAGES = Set.of("core", "identity", "storage");

  private final PackagesService packages;
  private final StateFileService stateFiles;
  private final JobService jobs;
  private final CommandRunner commands;

  public PackageLifecycleService(PackagesService packages, StateFileService stateFiles,
                                 JobService jobs, CommandRunner commands) {
    this.packages = packages;
    this.stateFiles = stateFiles;
    this.jobs = jobs;
    this.commands = commands;
  }

  /**
   * Install: enrol the package (plus its own {@code enabled[]} entry) and
   * start it. 404 unknown, 403 core, 409 already enabled.
   */
  public JobService.Job enable(String name) {
    Package pkg = requireExists(name);
    requireNotCore(pkg);
    if (pkg.enabled()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "package is already enabled");
    }

    List<String> newEnabled = mergeEnabled(name);
    List<String> argv = new ArrayList<>();
    argv.add("bash");
    argv.add("scripts/up.sh");
    argv.addAll(newEnabled);

    return jobs.submitCommand(JobService.Kind.ENABLE, name, repoRoot(), argv);
  }

  /**
   * Disable: stop the package's containers without un-enrolling it.
   * Reversible with a plain Start. 404 unknown, 403 core, 422 not
   * installed, 409 already stopped (nothing to disable).
   */
  public JobService.Job stop(String name) {
    Package pkg = requireExists(name);
    requireNotCore(pkg);
    requireEnabled(pkg);
    if (!pkg.running()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "package is already stopped");
    }

    List<String> argv = List.of("bash", "scripts/down.sh", name);
    return jobs.submitCommand(JobService.Kind.STOP, name, repoRoot(), argv);
  }

  /**
   * Uninstall: stop the package's containers and remove it from
   * {@code enabled[]}. Data under {@code data/<name>} is never touched —
   * see class javadoc. Works from either RUNNING or STOPPED, matching the
   * existing "Uninstall works whether the app is running or stopped"
   * frontend behaviour. 404 unknown, 403 core, 422 not installed.
   */
  public JobService.Job uninstall(String name) {
    Package pkg = requireExists(name);
    requireNotCore(pkg);
    requireEnabled(pkg);

    List<String> argv = List.of("bash", "scripts/down.sh", name);
    Path workingDir = repoRoot();
    return jobs.submit(JobService.Kind.DISABLE, name, job -> {
      int exit = commands.stream(workingDir, Map.of("AURORA_INVOKED_BY", "aurora-dashboard"),
          argv, line -> jobs.append(job, line));
      if (exit != 0) {
        throw new JobService.JobFailedException("down.sh exited " + exit, exit);
      }
      removeFromEnabled(name);
      jobs.append(job, "[aurora] " + name + " has been uninstalled. Its data under data/"
          + name + " was left in place — remove it by hand for a clean slate.");
    });
  }

  // ------------------------------------------------------------------
  // Guards
  // ------------------------------------------------------------------

  private Package requireExists(String name) {
    return packages.find(name).orElseThrow(() ->
        new ResponseStatusException(HttpStatus.NOT_FOUND, "no such package"));
  }

  private void requireNotCore(Package pkg) {
    if (CORE_PACKAGES.contains(pkg.name())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "core packages can't be added, started, stopped, or removed from here");
    }
  }

  private void requireEnabled(Package pkg) {
    if (!pkg.enabled()) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "package is not installed");
    }
  }

  // ------------------------------------------------------------------
  // .state.yml helpers
  // ------------------------------------------------------------------

  private Path repoRoot() {
    return stateFiles.repoRoot();
  }

  /** Current enabled[] plus {@code name}, preserving order, deduplicated. */
  private List<String> mergeEnabled(String name) {
    var enabled = stateFiles.readState().enabled();
    Set<String> merged = new LinkedHashSet<>(enabled == null ? List.of() : enabled);
    merged.add(name);
    return List.copyOf(merged);
  }

  private void removeFromEnabled(String name) {
    var enabled = stateFiles.readState().enabled();
    List<String> without = new ArrayList<>(enabled == null ? List.of() : enabled);
    without.remove(name);
    stateFiles.writeEnabled(without);
  }
}
