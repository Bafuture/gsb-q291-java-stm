package com.example.gsb.stm;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A transactional reference. Reads and writes must happen inside an
 * {@link Stm#atomic} block, where they are recorded in the transaction log.
 * Writes stay private to the transaction until it commits.
 *
 * @param <T> value type
 */
public final class StmRef<T> {

    /** Immutable cell: version, value and the transaction that wrote it. */
    record VersionedValue<T>(long version, T value, long writerTxId) {
    }

    private final Stm owner;
    private final long internalId;
    private final String name;
    private final ReentrantLock commitLock = new ReentrantLock();
    private final AtomicReference<VersionedValue<T>> cell;

    StmRef(Stm owner, String name, T initialValue) {
        this.owner = owner;
        this.internalId = owner.nextRefId();
        this.name = (name == null || name.isBlank()) ? "ref-" + internalId : name;
        this.cell = new AtomicReference<>(new VersionedValue<>(owner.nextVersion(), initialValue, 0L));
    }

    /** Reads through the current transaction log (read-your-writes). */
    public T get() {
        return owner.requireTransaction().read(this);
    }

    /** Buffers a write in the current transaction log; last write wins. */
    public void set(T newValue) {
        owner.requireTransaction().write(this, newValue);
    }

    /** Diagnostic name used in conflict messages. */
    public String name() {
        return name;
    }

    /** Current committed version; for diagnostics and tests. */
    public long currentVersion() {
        return cell.get().version();
    }

    VersionedValue<T> current() {
        return cell.get();
    }

    @SuppressWarnings("unchecked")
    void commitValue(long version, Object value, long writerTxId) {
        cell.set(new VersionedValue<>(version, (T) value, writerTxId));
    }

    long internalId() {
        return internalId;
    }

    void lock() {
        commitLock.lock();
    }

    void unlock() {
        commitLock.unlock();
    }

    @Override
    public String toString() {
        return "StmRef[" + name + "]";
    }
}
