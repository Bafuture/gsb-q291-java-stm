package com.example.gsb.stm;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Software transactional memory runtime. Create one instance, allocate
 * {@link StmRef}s from it, and mutate them inside {@link #atomic} blocks.
 * Conflicting blocks are retried transparently up to the configured limits.
 */
public final class Stm {

    private final StmConfig config;
    private final AtomicLong txIds = new AtomicLong();
    private final AtomicLong refIds = new AtomicLong();
    private final AtomicLong versionClock = new AtomicLong();
    private final ThreadLocal<Transaction> currentTx = new ThreadLocal<>();

    private final AtomicLong transactions = new AtomicLong();
    private final AtomicLong commits = new AtomicLong();
    private final AtomicLong conflictRetries = new AtomicLong();

    public Stm() {
        this(StmConfig.defaultConfig());
    }

    public Stm(StmConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /** Creates a transactional reference with an auto-generated diagnostic name. */
    public <T> StmRef<T> newRef(T initialValue) {
        return newRef(null, initialValue);
    }

    /** Creates a transactional reference; the name shows up in conflict messages. */
    public <T> StmRef<T> newRef(String name, T initialValue) {
        return new StmRef<>(this, name, initialValue);
    }

    /** Runs {@code block} as one atomic read-write transaction, retrying on conflict. */
    public <T> T atomic(Supplier<T> block) {
        return runAtomic(false, block);
    }

    /** Runs {@code block} as one atomic read-write transaction, retrying on conflict. */
    public void atomic(Runnable block) {
        runAtomic(false, () -> {
            block.run();
            return null;
        });
    }

    /**
     * Runs {@code block} as an atomic read-only transaction. It never writes, so it
     * can never cause write conflicts for others; it still retries if its own reads
     * are invalidated before commit.
     */
    public <T> T atomicReadOnly(Supplier<T> block) {
        return runAtomic(true, block);
    }

    /** Read-only variant of {@link #atomic(Runnable)}. */
    public void atomicReadOnly(Runnable block) {
        runAtomic(true, () -> {
            block.run();
            return null;
        });
    }

    /** Snapshot of runtime counters: transactions, commits, conflict retries, average retries. */
    public StmStats stats() {
        long txCount = transactions.get();
        long retries = conflictRetries.get();
        double average = txCount == 0 ? 0.0 : (double) retries / txCount;
        return new StmStats(txCount, commits.get(), retries, average);
    }

    /** Id of the transaction currently bound to this thread; for diagnostics. */
    public long currentTransactionId() {
        return requireTransaction().id();
    }

    private <T> T runAtomic(boolean readOnly, Supplier<T> block) {
        Objects.requireNonNull(block, "block");
        Transaction enclosing = currentTx.get();
        if (enclosing != null) {
            if (enclosing.owner() != this) {
                throw new IllegalStateException(
                        "cannot nest an atomic block of a different Stm instance");
            }
            return block.get();
        }
        transactions.incrementAndGet();
        long startedNanos = System.nanoTime();
        long deadlineNanos = startedNanos + config.maxRetryDuration().toNanos();
        int retries = 0;
        while (true) {
            Transaction tx = new Transaction(this, txIds.incrementAndGet(), readOnly);
            currentTx.set(tx);
            try {
                T result = block.get();
                tx.commit();
                commits.incrementAndGet();
                return result;
            } catch (StmConflictException conflict) {
                if (retries >= config.maxRetries() || System.nanoTime() >= deadlineNanos) {
                    throw new StmRetryExhaustedException(tx.id(), retries,
                            System.nanoTime() - startedNanos, config, conflict);
                }
                retries++;
                conflictRetries.incrementAndGet();
                backoff(retries);
            } finally {
                currentTx.remove();
            }
        }
    }

    private static void backoff(int retries) {
        int shift = Math.min(retries - 1, 6);
        LockSupport.parkNanos(1_000L << shift);
    }

    Transaction requireTransaction() {
        Transaction tx = currentTx.get();
        if (tx == null) {
            throw new IllegalStateException(
                    "no active transaction: access StmRef only inside an atomic block");
        }
        return tx;
    }

    Transaction currentTransaction() {
        return currentTx.get();
    }

    long nextRefId() {
        return refIds.incrementAndGet();
    }

    long nextVersion() {
        return versionClock.incrementAndGet();
    }
}
