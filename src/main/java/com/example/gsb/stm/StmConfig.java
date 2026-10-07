package com.example.gsb.stm;

import java.time.Duration;

/**
 * Retry limits applied when an atomic block keeps conflicting.
 *
 * @param maxRetries       maximum number of conflict-driven retries after the initial attempt
 * @param maxRetryDuration maximum total wall-clock time spent trying to commit one atomic block
 */
public record StmConfig(int maxRetries, Duration maxRetryDuration) {

    public StmConfig {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0, got " + maxRetries);
        }
        if (maxRetryDuration == null || maxRetryDuration.isZero() || maxRetryDuration.isNegative()) {
            throw new IllegalArgumentException("maxRetryDuration must be a positive duration");
        }
    }

    public static StmConfig defaultConfig() {
        return new StmConfig(100, Duration.ofSeconds(10));
    }
}
