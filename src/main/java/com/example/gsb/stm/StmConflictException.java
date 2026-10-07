package com.example.gsb.stm;

/**
 * Raised when commit-time validation finds that a reference read by the current
 * transaction was overwritten by another transaction. Carries the exact reference
 * and the id of the transaction that rewrote it.
 */
public final class StmConflictException extends StmException {

    private final long txId;
    private final String refName;
    private final long expectedVersion;
    private final long actualVersion;
    private final long writerTxId;

    StmConflictException(long txId, String refName, long expectedVersion, long actualVersion, long writerTxId) {
        super("transaction " + txId + " conflicted on ref '" + refName + "': read version "
                + expectedVersion + " but transaction " + writerTxId
                + " overwrote it (now version " + actualVersion + ")");
        this.txId = txId;
        this.refName = refName;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
        this.writerTxId = writerTxId;
    }

    /** Id of the transaction that detected the conflict. */
    public long txId() {
        return txId;
    }

    /** Name of the reference whose read version no longer matches. */
    public String refName() {
        return refName;
    }

    /** Version the conflicting transaction originally read. */
    public long expectedVersion() {
        return expectedVersion;
    }

    /** Version the reference has now. */
    public long actualVersion() {
        return actualVersion;
    }

    /** Id of the transaction that committed the overwriting write. */
    public long writerTxId() {
        return writerTxId;
    }
}
