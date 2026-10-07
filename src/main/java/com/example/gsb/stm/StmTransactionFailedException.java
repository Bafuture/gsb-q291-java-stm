package com.example.gsb.stm;

import java.time.Duration;

/**
 * Thrown when an atomic block exhausts its retry budget: too many conflicts
 * or the total time limit elapsed. The cause is the last
 * {@link StmConflictException}, so the failure pinpoints the ref and the
 * transaction that rewrote it.
 */
public final class StmTransactionFailedException extends RuntimeException {

    private final int retries;
    private final Duration elapsed;

    StmTransactionFailedException(int retries, Duration elapsed, StmConflictException lastConflict) {
        super("transaction failed after " + retries + " retries in "
                + elapsed.toMillis() + " ms; last conflict: " + lastConflict.getMessage(),
                lastConflict);
        this.retries = retries;
        this.elapsed = elapsed;
    }

    public int retries() {
        return retries;
    }

    public Duration elapsed() {
        return elapsed;
    }

    public StmConflictException lastConflict() {
        return (StmConflictException) getCause();
    }
}
