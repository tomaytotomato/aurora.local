package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.Package;
import com.tomaytotomato.aurora.services.JobService;
import com.tomaytotomato.aurora.services.PackageLifecycleService;
import com.tomaytotomato.aurora.services.PackagesService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/packages")
public class PackagesController {

  private final PackagesService packages;
  private final PackageLifecycleService lifecycle;

  public PackagesController(PackagesService packages, PackageLifecycleService lifecycle) {
    this.packages = packages;
    this.lifecycle = lifecycle;
  }

  @GetMapping
  public List<Package> list() {
    return packages.list();
  }

  /**
   * Package detail. openapi.yaml's {@code PackageDetail} schema is a flat
   * object (an extension of {@code PackageSummary}) — this used to wrap
   * {@link Package} inside {@code {package, env_example}}, which meant
   * every field the frontend reads (name, enabled, running, category…)
   * came back {@code undefined}. On the app detail page that showed up as
   * core packages reading DISABLED with a nonsensical "Add app" button:
   * {@code isCorePackage()} looks up {@code p.name}, and a name that is
   * always undefined never matches. {@code env_example} was dead weight —
   * nothing on the frontend ever read it; env values come from the
   * separate {@code /packages/{name}/env} endpoint.
   */
  @GetMapping("/{name}")
  public ResponseEntity<Package> get(@PathVariable String name) {
    return packages.find(name)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * Install: enrol the package and start it. See
   * {@link PackageLifecycleService#enable} for the full state-machine
   * story — this is the "Install" button on the app detail page.
   */
  @PostMapping("/{name}/enable")
  public ResponseEntity<Map<String, Object>> enable(@PathVariable String name) {
    JobService.Job job = lifecycle.enable(name);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("jobId", job.id));
  }

  /**
   * Disable: stop the package's containers, keep it enrolled. This is
   * new — see {@link PackageLifecycleService} class javadoc for why it's
   * distinct from {@link #disable}.
   */
  @PostMapping("/{name}/stop")
  public ResponseEntity<Map<String, Object>> stop(@PathVariable String name) {
    JobService.Job job = lifecycle.stop(name);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("jobId", job.id));
  }

  /**
   * Uninstall: stop the package's containers and un-enrol it. Data is
   * preserved — see {@link PackageLifecycleService#uninstall}.
   */
  @PostMapping("/{name}/disable")
  public ResponseEntity<Map<String, Object>> disable(@PathVariable String name) {
    JobService.Job job = lifecycle.uninstall(name);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("jobId", job.id));
  }
}
