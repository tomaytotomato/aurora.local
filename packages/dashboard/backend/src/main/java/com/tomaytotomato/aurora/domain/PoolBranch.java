package com.tomaytotomato.aurora.domain;

/** One mergerfs branch — a data disk that is part of the pool. */
public record PoolBranch(String diskId, String path, long totalBytes, long usedBytes) {}
