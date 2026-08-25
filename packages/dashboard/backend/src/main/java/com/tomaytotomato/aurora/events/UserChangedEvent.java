package com.tomaytotomato.aurora.events;

/**
 * Phase D iter-3 \u2014 emitted when Aurora's user set changes shape
 * (create / update / role change / password rotation / delete). Consumed
 * by {@link com.tomaytotomato.aurora.services.AutheliaService} to
 * re-project the users into {@code data/authelia/users_database.yml}.
 *
 * <p>Deliberately payload-free: the projector re-reads the full users
 * list each fire so a race between two mutations resolves to whichever
 * projection runs last, and the yaml file always matches the DB at
 * projection time. If throughput ever becomes an issue we can grow this
 * into a coalescing token, but for a homelab-sized user set (units of
 * users, not thousands) a full re-read is comfortably under a
 * millisecond and much simpler to reason about.
 *
 * <p>Emitted via Spring's standard {@code ApplicationEventPublisher};
 * consumers subscribe with {@code @EventListener(UserChangedEvent.class)}.
 */
public record UserChangedEvent(String reason) {
  /** Kind constants for the {@code reason} slot \u2014 log-scrutable. */
  public static final String CREATE = "create";
  public static final String UPDATE = "update";
  public static final String ROLE_CHANGE = "role-change";
  public static final String PASSWORD_ROTATE = "password-rotate";
  public static final String DELETE = "delete";
  public static final String STARTUP = "startup";
  public static final String RECONCILE = "reconcile";
}
