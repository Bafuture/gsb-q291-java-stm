package com.example.gsb.stm;

import java.time.Duration;

/**
 * Raised when an atomic block keeps conflicting until it runs out of retries or
 * out of time. The message and {@link #lastConflict()} identify the reference
 * and the transaction that overwrote it on the final failed attempt.
 */
public final class StmRetryExhaustedException extends StmException {

    private final long txId;
    private final int retries;
    private final Duration elapsed;
    private final StmConfig config;
    private final StmConflictException lastConflict;

    StmRetryExhaustedException(long txId, int retries, long elapsedNanos, StmConfig config,
                               StmConflictException lastConflict) {
        super("transaction " + txId + " gave up after " + retries + " conflict retries in "
                + Duration.ofNanos(elapsedNanos).toMillis() + " ms (limits: maxRetries=" + config.maxRetries()
                + ", maxRetryDuration=" + config.maxRetryDuration() + "); last conflict: "
                + lastConflict.getMessage(), lastConflict);
        this.txId = txId;
        this.retries = retries;
        this.elapsed = Duration.ofNanos(elapsedNanos);
        this.config = config;
        this.lastConflict = lastConflict;
    }

    /** Id of the transaction attempt that finally gave up. */
    public long txId() {
        return txId;
    }

    /** How many conflict retries were performed before giving up. */
    public int retries() {
        return retries;
    }

    /** Total wall-clock time spent on the atomic block, including retries. */
    public Duration elapsed() {
        return elapsed;
    }

    /** The retry limits that were in effect. */
    public StmConfig config() {
        return config;
    }

    /** The conflict detected on the final attempt; names the ref and the writer transaction. */
    public StmConflictException lastConflict() {
        return lastConflict;
    }
}
