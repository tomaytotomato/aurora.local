package com.tomaytotomato.aurora.domain;

public record AdminUser(
    long id,
    String username,
    String passwordHash,
    String tz,
    String createdAt
) {}
