package com.example.gsb.stm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * A software transactional memory runtime.
 *
 * <p>Usage:
 * <pre>{@code
 * Stm stm = new Stm();
 * StmRef<Integer> a = stm.newRef("a", 100);
 * StmRef<Integer> b = stm.newRef("b", 0);
 * stm.atomically(tx -> {
 *     tx.write(a, tx.read(a) - 50);
 *     tx.write(b, tx.read(b) + 50);
 * });
 * }</pre>
 *
 * <p>Commit is optimistic: the block runs without holding global locks, and at
 * commit time the read set is validated. If any read ref was overwritten by
 * another transaction in the meantime, a {@link StmConflictException} is
 * raised internally and the whole block is retried, subject to the retry
 * count and wall-clock limits of {@link StmConfig}. Exhausting the budget
 * throws {@link StmTransactionFailedException}.
 */
public final class Stm {

    private final AtomicLong refIds = new AtomicLong();
    private final AtomicLong txIds = new AtomicLong();

    private final AtomicLong transactions = new AtomicLong();
    private final AtomicLong commits = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong conflictRetries = new AtomicLong();

    /** Creates a transactional reference with a human-readable name (used in conflict messages). */
    public <T> StmRef<T> newRef(String name, T initial) {
        return new StmRef<>(refIds.incrementAndGet(), name, initial);
    }

    /** Runs the block atomically with the default retry policy. */
    public <T> T atomically(Function<Transaction, T> block) {
        return atomically(StmConfig.defaults(), block);
    }

    /** Runs the block atomically, retrying the whole block on conflict. */
    public <T> T atomically(StmConfig config, Function<Transaction, T> block) {
        return run(config, false, block);
    }

    /**
     * Runs a read-only atomic block. Read-only transactions never write, so
     * they never cause write conflicts in other transactions; they are still
     * validated against their read set at commit time.
     */
    public <T> T readOnly(Function<Transaction, T> block) {
        return readOnly(StmConfig.defaults(), block);
    }

    public <T> T readOnly(StmConfig config, Function<Transaction, T> block) {
        return run(config, true, block);
    }

    public StmStats stats() {
        return new StmStats(
                transactions.get(), commits.get(), failures.get(), conflictRetries.get());
    }

    private <T> T run(StmConfig config, boolean readOnly, Function<Transaction, T> block) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(block, "block");
        transactions.incrementAndGet();
        long deadline = System.nanoTime() + config.maxDuration().toNanos();
        int retries = 0;
        while (true) {
            Transaction tx = new Transaction(txIds.incrementAndGet(), readOnly);
            try {
                T result = block.apply(tx);
                commit(tx);
                commits.incrementAndGet();
                return result;
            } catch (StmConflictException conflict) {
                retries++;
                conflictRetries.incrementAndGet();
                Duration elapsed = elapsedSince(deadline, config.maxDuration());
                if (retries > config.maxRetries() || System.nanoTime() >= deadline) {
                    failures.incrementAndGet();
                    throw new StmTransactionFailedException(retries, elapsed, conflict);
                }
            } catch (RuntimeException | Error e) {
                failures.incrementAndGet();
                throw e;
            }
        }
    }

    private static Duration elapsedSince(long deadline, Duration budget) {
        long remainingNanos = deadline - System.nanoTime();
        long elapsedNanos = budget.toNanos() - Math.max(remainingNanos, 0);
        return Duration.ofNanos(Math.max(elapsedNanos, 0));
    }

    /**
     * Validates the read set and, if it holds, applies the write set.
     * Write-set refs are locked in a global id order, so concurrent commits
     * cannot deadlock.
     */
    private void commit(Transaction tx) {
        Map<StmRef<?>, Object> writes = tx.writeSet();
        if (writes.isEmpty()) {
            validateReadSet(tx);
            return;
        }
        List<StmRef<?>> locked = new ArrayList<>(writes.keySet());
        locked.sort(Comparator.comparingLong(StmRef::id));
        for (StmRef<?> ref : locked) {
            ref.lock();
        }
        try {
            validateReadSet(tx);
            for (Map.Entry<StmRef<?>, Object> entry : writes.entrySet()) {
                commitWrite(entry.getKey(), entry.getValue(), tx.id());
            }
        } finally {
            for (StmRef<?> ref : locked) {
                ref.unlock();
            }
        }
    }

    private void validateReadSet(Transaction tx) {
        for (Map.Entry<StmRef<?>, Long> entry : tx.readSet().entrySet()) {
            StmRef<?> ref = entry.getKey();
            if (ref.version() != entry.getValue()) {
                throw new StmConflictException(tx.id(), ref, entry.getValue());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void commitWrite(StmRef<T> ref, Object value, long writerTxId) {
        ref.commitWrite((T) value, writerTxId);
    }
}
