package com.tomaytotomato.aurora.domain;

/**
 * A person who can sign in to the dashboard.
 *
 * <p>Three roles, not a permission matrix. A home server shared with a
 * partner or a housemate needs "can look", "can start things" and "can
 * change who else gets in"; anything finer would be configuration nobody
 * maintains.
 *
 * @param role            one of {@code admin}, {@code operator}, {@code viewer}
 * @param lastLoginAt     ISO-8601 UTC, or null when they never have
 * @param passkeyEnrolled always false for now; the column exists so the
 *                        API can report the truth rather than omit a field
 *                        the frontend already reads
 */
public record AdminUser(
    long id,
    String username,
    String passwordHash,
    String tz,
    String createdAt,
    String role,
    String lastLoginAt,
    boolean passkeyEnrolled
) {

  /** Full control, including managing other people. */
  public static final String ROLE_ADMIN = "admin";
  /** Start and stop apps, edit configuration. Cannot manage people. */
  public static final String ROLE_OPERATOR = "operator";
  /** Read-only. */
  public static final String ROLE_VIEWER = "viewer";

  /**
   * Pre-roles shape, kept so callers written before roles keep compiling.
   * Defaults to {@code admin}, because the only user that existed before
   * roles was the box's owner and demoting them on upgrade would lock them
   * out of their own dashboard.
   */
  public AdminUser(long id, String username, String passwordHash, String tz, String createdAt) {
    this(id, username, passwordHash, tz, createdAt, ROLE_ADMIN, null, false);
  }

  public boolean isAdmin() {
    return ROLE_ADMIN.equals(role);
  }

  public static boolean isValidRole(String candidate) {
    return ROLE_ADMIN.equals(candidate)
        || ROLE_OPERATOR.equals(candidate)
        || ROLE_VIEWER.equals(candidate);
  }
}
