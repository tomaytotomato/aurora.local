package com.tomaytotomato.aurora.domain;

/**
 * Aurora-managed user.
 *
 * <p>Phase D (2026-08-03) added {@link #role()}. Pre-Phase-D rows were
 * always the wizard-created primary admin; the V3 migration backfills
 * every existing row to {@link Role#ADMIN}. New rows go through
 * {@code AdminUserRepo.create(..., Role)} and the DB triggers reject
 * anything outside the enum.
 *
 * <p>Name kept as {@code AdminUser} for backward compatibility across
 * the codebase; a "regular" user is now just an {@code AdminUser} with
 * {@code role == Role.USER}. Renaming to something like
 * {@code AuroraUser} is deferred to a Phase E cleanup so this iter
 * stays scoped to the schema change.
 */
public record AdminUser(
    long id,
    String username,
    String passwordHash,
    String tz,
    String createdAt,
    Role role
) {}
