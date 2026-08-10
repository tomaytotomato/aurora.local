package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.persistence.AuditEventRepo;
import com.tomaytotomato.aurora.services.CurrentUserService;
import com.tomaytotomato.aurora.services.StateFileService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /api/system/export} and {@code /api/system/import} — take the
 * box's configuration with you.
 *
 * <p>The case this exists for is a reinstall, a move to better hardware,
 * or a disk that died. Each of those currently means doing the wizard
 * again from memory and remembering which nine things you changed
 * afterwards.
 *
 * <p><b>The file carries no secrets.</b> {@code .env} values stay on the
 * box. That is what makes it safe to keep alongside ordinary backups, and
 * it is also why an import can never be the whole story — so the result
 * says plainly what it could not restore rather than letting someone
 * believe they are finished.
 *
 * <p>Import previews by default. One that silently enabled nine packages
 * is not something anyone should discover afterwards.
 */
@RestController
@RequestMapping("/api/system")
public class SettingsPortabilityController {

  /** Bumped when the shape changes in a way an older file cannot satisfy. */
  static final int SCHEMA_VERSION = 1;

  private final StateFileService state;
  private final AuditEventRepo audit;
  private final CurrentUserService currentUser;

  public SettingsPortabilityController(StateFileService state, AuditEventRepo audit,
                                       CurrentUserService currentUser) {
    this.state = state;
    this.audit = audit;
    this.currentUser = currentUser;
  }

  @GetMapping("/export")
  public Map<String, Object> export() {
    var repoState = state.readState();

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("version", SCHEMA_VERSION);
    out.put("exportedAt", Instant.now().toString());
    out.put("hostname", repoState.hostname());
    out.put("domain", repoState.domain());
    out.put("enabledPackages", repoState.enabled() == null ? List.of() : repoState.enabled());
    out.put("profiles", repoState.profiles() == null ? List.of() : repoState.profiles());
    out.put("dnsMode", null);
    // Non-secret preferences. Empty for now: the domains that will fill it
    // (notification channels, backup policy, edge protection, custom
    // routes) are later stages, and an export that invented keys nothing
    // reads would be worse than an honest empty object.
    out.put("settings", Map.of());

    audit.record(currentUser.currentUserId().orElse(null), "system.export", null, null);
    return out;
  }

  @PostMapping("/import")
  public Map<String, Object> importSettings(
      @RequestBody Map<String, Object> payload,
      @RequestParam(name = "preview", required = false) Integer preview) {

    Object rawVersion = payload == null ? null : payload.get("version");
    int version = rawVersion instanceof Number n ? n.intValue() : -1;
    if (version != SCHEMA_VERSION) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "That file came from a different version of Aurora and can't be read.");
    }

    boolean isPreview = preview != null && preview == 1;

    List<String> packages = new ArrayList<>();
    Object rawPackages = payload.get("enabledPackages");
    if (rawPackages instanceof List<?> list) {
      for (Object o : list) {
        if (o instanceof String s && !s.isBlank()) packages.add(s);
      }
    }

    List<String> applied = new ArrayList<>();
    List<String> skipped = new ArrayList<>();

    if (!packages.isEmpty()) {
      applied.add(packages.size() + " apps enabled");
    }
    Object domain = payload.get("domain");
    if (domain instanceof String s && !s.isBlank()) {
      applied.add("domain " + s);
    }
    // Always true, and worth saying every single time: the file never had
    // them, so nobody should walk away thinking the box is ready.
    skipped.add("secrets — those never leave the box, so each app needs its .env filled in again");

    if (!isPreview) {
      if (!packages.isEmpty()) {
        state.writeEnabled(packages);
      }
      if (domain instanceof String s && !s.isBlank()) {
        state.writeDomain(s);
      }
      audit.record(currentUser.currentUserId().orElse(null), "system.import", null,
          "{\"packages\":" + packages.size() + "}");
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("applied", applied);
    out.put("skipped", skipped);
    out.put("preview", isPreview);
    return out;
  }
}
