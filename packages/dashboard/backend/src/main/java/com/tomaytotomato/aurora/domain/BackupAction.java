package com.tomaytotomato.aurora.domain;

/**
 * Something that has to happen before a path can be snapshotted
 * consistently — typically a database dump, because copying a live
 * database's files gives you a backup that restores into a corrupt
 * database.
 *
 * <p>Read-only over the API: this is a statement of what the package
 * declared in its manifest, not a job to run.
 *
 * <p>Unlike its siblings this record is <em>not</em> annotated
 * {@code NON_NULL}. {@code openapi.yaml} lists {@code container} as
 * required-but-nullable, so a {@code command} action that belongs to no
 * container has to send an explicit {@code null}; omitting the key would
 * fail the schema's {@code required} check instead.
 */
public record BackupAction(
    String kind,
    String description,
    String container
) {
}
