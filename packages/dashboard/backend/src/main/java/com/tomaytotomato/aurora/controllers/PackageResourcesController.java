package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import com.tomaytotomato.aurora.services.CurrentUserService;
import com.tomaytotomato.aurora.services.PackageResourcesService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code /api/packages/{name}/resources} — the memory and CPU ceilings in
 * force for one package.
 *
 * <p>Its own controller rather than more methods on
 * {@link PackagesController}, which already has a full constructor and a
 * settled test suite.
 */
@RestController
@RequestMapping("/api/packages/{name}/resources")
public class PackageResourcesController {

  /** Below this a container cannot start at all, so it is not a useful cap. */
  static final int MIN_MEM_MB = 64;

  /**
   * Package names come straight off the URL and are used to build a
   * filesystem path, so they are checked here rather than trusted.
   */
  private static final Pattern PACKAGE_NAME = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");

  private final PackageResourcesService resources;
  private final AuditEventRepo audit;
  private final CurrentUserService currentUser;

  public PackageResourcesController(PackageResourcesService resources, AuditEventRepo audit,
                                    CurrentUserService currentUser) {
    this.resources = resources;
    this.audit = audit;
    this.currentUser = currentUser;
  }

  @GetMapping
  public Map<String, Object> get(@PathVariable("name") String name) {
    return resources.forPackage(validName(name));
  }

  @PutMapping
  public Map<String, Object> put(@PathVariable("name") String name,
                                 @RequestBody Map<String, Object> body) {
    String pkg = validName(name);

    Integer mem = PackageResourcesService.intOrNull(body == null ? null : body.get("memLimitMb"));
    Double cpus = PackageResourcesService.numberOrNull(body == null ? null : body.get("cpus"));

    if (mem != null && mem < MIN_MEM_MB) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "A memory ceiling below " + MIN_MEM_MB + " MB would stop the app starting at all.");
    }
    if (cpus != null && cpus <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "A CPU ceiling has to be greater than zero.");
    }

    Map<String, Object> saved = resources.setOverride(pkg, mem, cpus);
    audit.record(currentUser.currentUserId().orElse(null), "packages.resources", "package:" + pkg,
        "{\"memLimitMb\":" + mem + ",\"cpus\":" + cpus + "}");
    return saved;
  }

  private static String validName(String name) {
    if (name == null || !PACKAGE_NAME.matcher(name).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad package name");
    }
    return name;
  }
}
