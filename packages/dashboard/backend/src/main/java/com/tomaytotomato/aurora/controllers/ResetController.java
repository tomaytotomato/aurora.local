package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.Role;
import com.tomaytotomato.aurora.services.CurrentUserService;
import com.tomaytotomato.aurora.services.ResetService;
import com.tomaytotomato.aurora.services.ResetService.ResetHelperFailedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code /api/reset} — the in-dashboard "start over" (A8, closing A6).
 *
 * <p>Admin-only. Body must carry the literal string {@code "RESET"} so a
 * misclicked button cannot wipe the box; the frontend renders a modal
 * that makes the caller type it.
 *
 * <p>Returns 202 Accepted (rather than 200) because the work is not done
 * by the time the response is written: {@link ResetService} spawns a
 * detached helper container that does the destruction after a short
 * delay, precisely so that this response can leave the building before
 * the aurora container itself is torn down.
 */
@RestController
@RequestMapping("/api/reset")
public class ResetController {

  private static final Logger log = LoggerFactory.getLogger(ResetController.class);

  private final ResetService reset;
  private final CurrentUserService currentUser;

  public ResetController(ResetService reset, CurrentUserService currentUser) {
    this.reset = reset;
    this.currentUser = currentUser;
  }

  /**
   * Kick off the reset. See {@link ResetService#start(Long)} for what the
   * helper does after the response returns.
   */
  @PostMapping
  public ResponseEntity<ResetAccepted> reset(@Valid @RequestBody ResetRequest body) {
    Long acting = requireAdmin();

    if (!ResetService.CONFIRM_TOKEN.equals(body.confirm())) {
      // Not a validation failure — a policy one. 400 with a copy the
      // frontend surfaces verbatim so the user sees the exact word.
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "type " + ResetService.CONFIRM_TOKEN + " to confirm");
    }

    log.warn("reset accepted (actingUserId={}); helper about to start", acting);
    String helperId;
    try {
      helperId = reset.start(acting);
    } catch (ResetHelperFailedException e) {
      // Nothing has been destroyed yet — the helper never started.
      // 500 with a plain-English message the UI shows.
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
          e.getMessage());
    }
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(new ResetAccepted(helperId));
  }

  /** Body of {@link #reset(ResetRequest)}. Only field is the confirm word. */
  public record ResetRequest(
      @NotBlank
      String confirm) {}

  /** What the frontend renders on the "goodbye" screen. Container id is for logs, not display. */
  public record ResetAccepted(String helperId) {}

  private Long requireAdmin() {
    var role = currentUser.currentRole();
    if (role.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "sign in required");
    }
    if (role.get() != Role.ADMIN) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin role required");
    }
    return currentUser.currentUserId().orElse(null);
  }
}
