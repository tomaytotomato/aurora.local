package com.tomaytotomato.aurora.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What one environment variable is <em>for</em> — not what it is set to.
 *
 * <p>Parsed from a package's {@code .env.example}, which is the only place
 * that records a variable's purpose: the comment above it and the example
 * value beside it. Deliberately carries no value: {@code GET
 * /packages/{name}/env} is the endpoint for values, and it masks secrets
 * unless asked not to. A spec list that quietly included values would
 * leak every secret on the box to anything that could read a package
 * page.
 *
 * <p>{@code example} and {@code comment} are omitted rather than sent as
 * {@code null} because {@code openapi.yaml} types both as plain strings,
 * and a null would fail the response-schema check for a variable that
 * simply has no comment above it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EnvVarSpec(
    String key,
    String example,
    String comment,
    boolean secret,
    boolean required
) {
}
