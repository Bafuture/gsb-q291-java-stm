package com.example.gsb.stm;

import java.time.Duration;
import java.util.Objects;

/**
 * Retry policy for an atomic block.
 *
 * @param maxRetries  maximum number of conflict-driven retries after the first
 *                    attempt (so the block runs at most {@code maxRetries + 1} times)
 * @param maxDuration wall-clock budget across all attempts of one atomic block
 */
public record StmConfig(int maxRetries, Duration maxDuration) {

    public StmConfig {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0");
        }
        Objects.requireNonNull(maxDuration, "maxDuration");
        if (maxDuration.isNegative() || maxDuration.isZero()) {
            throw new IllegalArgumentException("maxDuration must be positive");
        }
    }

    public static StmConfig defaults() {
        return new StmConfig(100, Duration.ofSeconds(10));
    }
}
