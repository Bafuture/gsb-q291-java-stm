package com.example.gsb.stm;

/**
 * Immutable snapshot of STM statistics.
 */
public final class StmStats {

    private final long transactions;
    private final long commits;
    private final long failures;
    private final long conflictRetries;

    StmStats(long transactions, long commits, long failures, long conflictRetries) {
        this.transactions = transactions;
        this.commits = commits;
        this.failures = failures;
        this.conflictRetries = conflictRetries;
    }

    /** Number of atomic blocks started (logical transactions, not attempts). */
    public long transactions() {
        return transactions;
    }

    /** Number of atomic blocks that committed successfully. */
    public long commits() {
        return commits;
    }

    /** Number of atomic blocks that failed (retry budget exhausted or aborted by an exception). */
    public long failures() {
        return failures;
    }

    /** Total number of conflict-driven retries across all transactions. */
    public long conflictRetries() {
        return conflictRetries;
    }

    /** Average number of retry rounds per completed transaction. */
    public double averageRetries() {
        long completed = commits + failures;
        return completed == 0 ? 0.0 : (double) conflictRetries / completed;
    }

    @Override
    public String toString() {
        return "StmStats{transactions=" + transactions
                + ", commits=" + commits
                + ", failures=" + failures
                + ", conflictRetries=" + conflictRetries
                + ", averageRetries=" + averageRetries() + "}";
    }
}
