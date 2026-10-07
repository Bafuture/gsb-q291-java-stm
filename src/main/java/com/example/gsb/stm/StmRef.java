package com.example.gsb.stm;

import java.util.concurrent.locks.ReentrantLock;

/**
 * A transactional reference: a memory cell guarded by the STM.
 *
 * <p>Every committed write bumps {@link #version()} and records the id of the
 * transaction that performed it ({@link #lastWriterTxId()}). Reads inside a
 * transaction remember the version they observed, which is the basis of the
 * optimistic conflict check.
 */
public final class StmRef<T> {

    private final long id;
    private final String name;

    private final ReentrantLock lock = new ReentrantLock();

    private volatile T value;
    private volatile long version;
    private volatile long lastWriterTxId = -1L;

    StmRef(long id, String name, T initial) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ref name must not be null or blank");
        }
        this.id = id;
        this.name = name;
        this.value = initial;
    }

    public String name() {
        return name;
    }

    public long version() {
        return version;
    }

    /** Id of the transaction whose committed write produced the current value, or {@code -1} if never written. */
    public long lastWriterTxId() {
        return lastWriterTxId;
    }

    /**
     * Non-transactional peek at the current value. Intended for diagnostics and
     * assertions; code inside an atomic block must use {@link Transaction#read}.
     */
    public T peek() {
        lock.lock();
        try {
            return value;
        } finally {
            lock.unlock();
        }
    }

    long id() {
        return id;
    }

    void lock() {
        lock.lock();
    }

    void unlock() {
        lock.unlock();
    }

    /** Reads value and version atomically. */
    Snapshot<T> snapshot() {
        lock.lock();
        try {
            return new Snapshot<>(value, version);
        } finally {
            lock.unlock();
        }
    }

    /** Applies a committed write: the last write wins and the version advances exactly once. */
    void commitWrite(T newValue, long writerTxId) {
        value = newValue;
        version++;
        lastWriterTxId = writerTxId;
    }

    record Snapshot<T>(T value, long version) {
    }
}
