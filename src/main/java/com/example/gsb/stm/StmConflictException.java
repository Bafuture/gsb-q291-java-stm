package com.example.gsb.stm;

/**
 * Signals an optimistic-concurrency conflict detected at commit time: some
 * ref in the transaction's read set was overwritten by another transaction
 * while this one was running. Carries the exact ref and the id of the
 * transaction that rewrote it.
 */
public final class StmConflictException extends RuntimeException {

    private final long transactionId;
    private final String refName;
    private final long expectedVersion;
    private final long actualVersion;
    private final long writerTxId;

    StmConflictException(long transactionId, StmRef<?> ref, long expectedVersion) {
        super("transaction #" + transactionId
                + " conflicted on ref '" + ref.name() + "'"
                + ": read version " + expectedVersion
                + ", but transaction #" + ref.lastWriterTxId()
                + " committed version " + ref.version());
        this.transactionId = transactionId;
        this.refName = ref.name();
        this.expectedVersion = expectedVersion;
        this.actualVersion = ref.version();
        this.writerTxId = ref.lastWriterTxId();
    }

    public long transactionId() {
        return transactionId;
    }

    public String refName() {
        return refName;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    public long actualVersion() {
        return actualVersion;
    }

    /** Id of the transaction whose committed write caused the conflict. */
    public long writerTxId() {
        return writerTxId;
    }
}
