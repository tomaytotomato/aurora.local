package com.tomaytotomato.aurora.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One mailbox as read back from Stalwart's JMAP account store.
 *
 * <p>Fields are exactly the ones a live Stalwart v0.16.19 returns on
 * {@code x:Account/get} (verified before this was written): {@code id},
 * {@code emailAddress} (the server assembles {@code name@domain}),
 * {@code createdAt}, and {@code usedDiskQuota}. {@code quotaBytes} comes
 * from the principal's {@code quotas} map when set; null means unlimited
 * (the common single-box case), and the UI hides the column rather than
 * rendering a bogus ceiling.
 *
 * <p>Deliberately omits an enabled/disabled flag: the v0.16.19 principal
 * object carries no such field, and the review's rule was "omit a column
 * rather than render undefined". If a later Stalwart exposes one, add it
 * here and light up the row toggle then.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MailboxSummary(
    String id,
    String address,
    Long usedBytes,
    Long quotaBytes,
    String createdAt
) {}
