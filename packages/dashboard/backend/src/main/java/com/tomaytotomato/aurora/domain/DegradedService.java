package com.tomaytotomato.aurora.domain;

/**
 * One broken container inside a package that is otherwise up, together
 * with the human explanation of what breaks for the operator while it
 * is down.
 *
 * <p>Introduced 2026-08-30 (review item 3). Item 2 taught the top strip
 * to say "Restarting" when any container inside a multi-container
 * package flipped. The next question ("which one, and what does it
 * take out?") was left to the operator to figure out from a docker
 * ps they cannot see. This record answers both, from the backend, so
 * Overview and the CoreServiceDetail view read the same source of
 * truth for the copy.
 *
 * <p>The reason strings live in {@link CoreServiceImpact}. Keeping the
 * record shape opaque here means the Package DTO does not have to
 * ship a copy of the impact map to the wire.
 */
public record DegradedService(String container, String reason) {
}
