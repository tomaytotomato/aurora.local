package com.tomaytotomato.aurora.security;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.tomaytotomato.aurora.domain.SecurityFinding;
import com.tomaytotomato.aurora.services.DockerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Flags any <em>non-core</em> container that is wired to core's shared
 * datastore ({@code core-db}) or cache ({@code core-cache}).
 *
 * <p>This is the runtime half of the core/non-core isolation boundary
 * from {@code docs/PACKAGE_CONTRACT.md} and
 * {@code docs/CORE_SHARED_SERVICES_PLAN.md}. The compile-time half is the
 * {@code core-isolation} CI job, which fails a non-core {@code compose.yml}
 * that references those hostnames. This rule catches the case CI can't:
 * a container that ended up joined to core's datastore on a <em>live</em>
 * box \u2014 a hand-edited compose, a copy-pasted stack, drift after a manual
 * {@code docker run}.
 *
 * <p>Why it matters: the whole point of isolating non-core apps is blast
 * radius. A non-core app that reaches into {@code core-db} couples its
 * fate to auth + mail \u2014 it can exhaust connections, fill the disk, or
 * corrupt shared infrastructure that has nothing to do with it. Core apps
 * share {@code core-db} by design; everyone else must own their own.
 *
 * <p>Detection: for each aurora-managed container that is <em>not</em>
 * owned by the {@code core} package, inspect its environment for a
 * reference to a core-internal hostname. An app talks to Postgres via a
 * connection string / host env var, so a {@code core-db} substring in its
 * env is a reliable, cheap signal without parsing every app's config
 * format. MEDIUM severity: it is a real coupling to fix, but the mount
 * alone is not proof of harm.
 */
@Component
public class CoreDbIsolationRule implements SecurityRule {

  private static final Logger log = LoggerFactory.getLogger(CoreDbIsolationRule.class);

  static final String RULE_ID = "core_db_isolation";
  static final String CORE_PACKAGE = "core";

  /** Core-internal service hostnames a non-core container must never use. */
  static final Set<String> CORE_INTERNAL_HOSTS = Set.of("core-db", "core-cache");

  private final DockerService docker;

  public CoreDbIsolationRule(DockerService docker) {
    this.docker = docker;
  }

  @Override
  public String id() {
    return RULE_ID;
  }

  @Override
  public List<SecurityFinding> evaluate() {
    List<SecurityFinding> out = new ArrayList<>();
    List<Container> containers;
    try {
      containers = docker.listProjectContainers();
    } catch (Exception e) {
      log.debug("core-db isolation rule: docker unavailable: {}", e.getMessage());
      return List.of();
    }

    for (Container c : containers) {
      String pkg = packageOf(c);
      // Unknown owner (no config_files label) or a core-owned container:
      // both are allowed to reference core-db. Only flag a container we can
      // positively attribute to a NON-core package.
      if (pkg == null || CORE_PACKAGE.equals(pkg)) continue;

      String name = containerName(c);
      String hit = coreHostInEnv(name);
      if (hit != null) {
        out.add(new SecurityFinding(
            RULE_ID + ":" + name,
            SecurityFinding.MEDIUM,
            "App \u201c" + pkg + "\u201d is using core's shared database",
            "The " + pkg + " app is connected to " + hit + ", which belongs to "
                + "the core stack (auth and mail). Apps are meant to keep their own "
                + "database inside their own stack, so a problem with one app can't "
                + "affect the others or take down sign-in. Give " + pkg + " its own "
                + "database, or remove it if it was added by mistake.",
            "/apps/" + pkg));
      }
    }
    return out;
  }

  /**
   * True-ish: returns the core hostname found in the container's env, or
   * null. Inspect can fail (container vanished mid-scan); treat that as
   * "no evidence" rather than throwing \u2014 rules must never propagate.
   */
  private String coreHostInEnv(String containerName) {
    try {
      InspectContainerResponse res = docker.rawInspect(containerName);
      if (res == null || res.getConfig() == null) return null;
      String[] env = res.getConfig().getEnv();
      if (env == null) return null;
      for (String entry : env) {
        if (entry == null) continue;
        for (String host : CORE_INTERNAL_HOSTS) {
          if (entry.contains(host)) return host;
        }
      }
    } catch (Exception e) {
      log.debug("core-db isolation rule: inspect {} failed: {}", containerName, e.getMessage());
    }
    return null;
  }

  private static String containerName(Container c) {
    if (c.getNames() == null || c.getNames().length == 0) return c.getId();
    String n = c.getNames()[0];
    return n.startsWith("/") ? n.substring(1) : n;
  }

  /**
   * Owning package from the compose config_files label, or null. The label
   * is a comma-separated list of the {@code -f} files; core is always the
   * first, and the owning package is the LAST {@code /packages/<name>/}
   * segment (matching the convention in
   * {@code PackagesService.runningPackageNames}). Taking the first would
   * mis-attribute every container to {@code core}.
   */
  private static String packageOf(Container c) {
    if (c.getLabels() == null) return null;
    String cfg = c.getLabels().get("com.docker.compose.project.config_files");
    if (cfg == null) return null;
    String owner = null;
    for (String seg : cfg.split(",")) {
      int i = seg.indexOf("/packages/");
      if (i < 0) continue;
      String rest = seg.substring(i + "/packages/".length());
      int slash = rest.indexOf('/');
      if (slash > 0) owner = rest.substring(0, slash);
    }
    return owner;
  }
}
