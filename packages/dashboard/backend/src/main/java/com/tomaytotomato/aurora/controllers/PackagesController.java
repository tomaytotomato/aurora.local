package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.Package;
import com.tomaytotomato.aurora.services.PackagesService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/packages")
public class PackagesController {

  private final PackagesService packages;

  public PackagesController(PackagesService packages) {
    this.packages = packages;
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
}
