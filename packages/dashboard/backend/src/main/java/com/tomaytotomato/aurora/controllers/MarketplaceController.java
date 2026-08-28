package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.MarketplaceApp;
import com.tomaytotomato.aurora.domain.MarketplaceStatus;
import com.tomaytotomato.aurora.services.CurrentUserService;
import com.tomaytotomato.aurora.services.MarketplaceCatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The marketplace catalogue surface: what apps the current index offers,
 * one app's full manifest bundle, the catalogue's provenance/update
 * status, and the two operator actions (refresh now, accept a pending
 * update).
 *
 * <p>See {@code docs/MARKETPLACE_HOSTING_PLAN.md} and
 * {@link MarketplaceCatalogService} for the invariants. This controller
 * is a thin shell: all the trust rules live in the service.
 */
@RestController
@RequestMapping("/api/marketplace")
public class MarketplaceController {

  private final MarketplaceCatalogService catalogue;
  private final CurrentUserService currentUser;

  public MarketplaceController(MarketplaceCatalogService catalogue, CurrentUserService currentUser) {
    this.catalogue = catalogue;
    this.currentUser = currentUser;
  }

  /** The active catalogue as summary cards (no embedded compose/readme). */
  @GetMapping
  public List<MarketplaceApp> list() {
    return catalogue.apps();
  }

  /** Catalogue provenance + whether a verified update is waiting. */
  @GetMapping("/status")
  public MarketplaceStatus status() {
    return catalogue.status();
  }

  /** One app with its embedded compose / .env.example / caddy / README. */
  @GetMapping("/{slug}")
  public ResponseEntity<MarketplaceApp> app(@PathVariable String slug) {
    return catalogue.app(slug).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
  }

  /** Fetch the remote index now; stages a newer verified one as available. */
  @PostMapping("/refresh")
  public MarketplaceStatus refresh() {
    return catalogue.refresh();
  }

  /** Accept the pending update, making it the active catalogue. */
  @PostMapping("/accept")
  public ResponseEntity<MarketplaceStatus> accept() {
    try {
      return ResponseEntity.ok(catalogue.accept(currentUser.currentUserId().orElse(null)));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(409).build();
    }
  }
}
