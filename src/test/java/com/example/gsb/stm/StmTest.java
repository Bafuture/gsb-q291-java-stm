package com.example.gsb.stm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class StmTest {

    @Test
    void atomicCommitAppliesAllWritesAtomically() {
        Stm stm = new Stm();
        StmRef<Integer> from = stm.newRef("from", 100);
        StmRef<Integer> to = stm.newRef("to", 0);

        stm.atomically(tx -> {
            tx.write(from, tx.read(from) - 40);
            tx.write(to, tx.read(to) + 40);
            return null;
        });

        assertThat(from.peek()).isEqualTo(60);
        assertThat(to.peek()).isEqualTo(40);
        assertThat(stm.stats().commits()).isEqualTo(1);
    }

    @Test
    void conflictingWriteCausesRetryAndEventualCommit() throws Exception {
        Stm stm = new Stm();
        StmRef<Integer> ref = stm.newRef("counter", 0);
        CyclicBarrier attemptStarted = new CyclicBarrier(2);
        CyclicBarrier interferenceDone = new CyclicBarrier(2);
        AtomicInteger attempts = new AtomicInteger();

        Thread interferer = new Thread(() -> {
            await(attemptStarted);
            stm.atomically(tx -> { tx.write(ref, 999); return null; });
            await(interferenceDone);
        });
        interferer.start();

        Integer observed = stm.atomically(tx -> {
            int value = tx.read(ref);
            if (attempts.getAndIncrement() == 0) {
                await(attemptStarted);
                await(interferenceDone);
            }
            tx.write(ref, value + 1);
            return value;
        });
        interferer.join(5_000);

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(observed).isEqualTo(999);
        assertThat(ref.peek()).isEqualTo(1000);
        assertThat(stm.stats().conflictRetries()).isEqualTo(1);
    }

    @Test
    void readOnlyTransactionsCommitConcurrentlyWithoutConflicts() throws Exception {
        Stm stm = new Stm();
        StmRef<Integer> a = stm.newRef("a", 1);
        StmRef<Integer> b = stm.newRef("b", 2);
        CyclicBarrier overlap = new CyclicBarrier(2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> stm.readOnly(tx -> {
                await(overlap);
                return tx.read(a) + tx.read(b);
            }));
            Future<Integer> second = pool.submit(() -> stm.readOnly(tx -> {
                await(overlap);
                return tx.read(a) + tx.read(b);
            }));

            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(3);
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }

        StmStats stats = stm.stats();
        assertThat(stats.commits()).isEqualTo(2);
        assertThat(stats.conflictRetries()).isZero();
        assertThat(stats.failures()).isZero();
    }

    @Test
    void readOnlyTransactionRejectsWrites() {
        Stm stm = new Stm();
        StmRef<Integer> ref = stm.newRef("guarded", 1);

        assertThatThrownBy(() -> stm.readOnly(tx -> {
            tx.write(ref, 2);
            return null;
        })).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("read-only")
          .hasMessageContaining("guarded");

        assertThat(ref.peek()).isEqualTo(1);
    }

    @Test
    void exceedingRetryLimitThrowsAndPinpointsRefAndWriter() throws Exception {
        Stm stm = new Stm();
        StmRef<Integer> ref = stm.newRef("contended", 0);
        CyclicBarrier attemptStarted = new CyclicBarrier(2);
        CyclicBarrier interferenceDone = new CyclicBarrier(2);
        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger attempts = new AtomicInteger();

        Thread interferer = interferer(stm, ref, attemptStarted, interferenceDone, done);
        interferer.start();

        StmConfig config = new StmConfig(3, Duration.ofSeconds(30));
        try {
            assertThatThrownBy(() -> stm.atomically(config, tx -> {
                tx.read(ref);
                attempts.incrementAndGet();
                await(attemptStarted);
                await(interferenceDone);
                tx.write(ref, -1);
                return null;
            }))
            .isInstanceOf(StmTransactionFailedException.class)
            .hasMessageContaining("'contended'")
            .hasMessageContaining("transaction #")
            .hasMessageContaining("4 retries");
        } finally {
            done.set(true);
            interferer.join(5_000);
        }

        assertThat(attempts.get()).isEqualTo(4);
        assertThat(stm.stats().failures()).isEqualTo(1);
        assertThat(stm.stats().conflictRetries()).isEqualTo(4);
    }

    @Test
    void failureExceptionExposesConflictingRefAndWriterTransaction() throws Exception {
        Stm stm = new Stm();
        StmRef<Integer> ref = stm.newRef("hot-ref", 0);
        CyclicBarrier attemptStarted = new CyclicBarrier(2);
        CyclicBarrier interferenceDone = new CyclicBarrier(2);
        AtomicBoolean done = new AtomicBoolean();

        Thread interferer = interferer(stm, ref, attemptStarted, interferenceDone, done);
        interferer.start();

        StmTransactionFailedException failure;
        try {
            failure = org.junit.jupiter.api.Assertions.assertThrows(
                    StmTransactionFailedException.class,
                    () -> stm.atomically(new StmConfig(1, Duration.ofSeconds(30)), tx -> {
                        tx.read(ref);
                        await(attemptStarted);
                        await(interferenceDone);
                        tx.write(ref, -1);
                return null;
                    }));
        } finally {
            done.set(true);
            interferer.join(5_000);
        }

        StmConflictException conflict = failure.lastConflict();
        assertThat(conflict).isNotNull();
        assertThat(conflict.refName()).isEqualTo("hot-ref");
        assertThat(conflict.writerTxId()).isPositive();
        assertThat(conflict.actualVersion()).isGreaterThan(conflict.expectedVersion());
        assertThat(failure.getMessage()).contains("hot-ref", "#" + conflict.writerTxId());
    }

    @Test
    void exceedingTimeLimitThrows() throws Exception {
        Stm stm = new Stm();
        StmRef<Integer> ref = stm.newRef("slow-ref", 0);
        CyclicBarrier attemptStarted = new CyclicBarrier(2);
        CyclicBarrier interferenceDone = new CyclicBarrier(2);
        AtomicBoolean done = new AtomicBoolean();

        Thread interferer = interferer(stm, ref, attemptStarted, interferenceDone, done);
        interferer.start();

        StmConfig config = new StmConfig(Integer.MAX_VALUE, Duration.ofMillis(200));
        try {
            assertThatThrownBy(() -> stm.atomically(config, tx -> {
                tx.read(ref);
                await(attemptStarted);
                await(interferenceDone);
                tx.write(ref, -1);
                return null;
            })).isInstanceOf(StmTransactionFailedException.class);
        } finally {
            done.set(true);
            interferer.join(5_000);
        }

        assertThat(stm.stats().failures()).isEqualTo(1);
    }

    @Test
    void repeatedWritesToSameRefKeepOnlyTheLast() {
        Stm stm = new Stm();
        StmRef<String> ref = stm.newRef("doc", "v0");
        long versionBefore = ref.version();

        stm.atomically(tx -> {
            tx.write(ref, "v1");
            tx.write(ref, "v2");
            tx.write(ref, "v3");
            return null;
        });

        assertThat(ref.peek()).isEqualTo("v3");
        assertThat(ref.version()).isEqualTo(versionBefore + 1);
    }

    @Test
    void abortedTransactionLeavesOthersCommittedValuesUntouched() {
        Stm stm = new Stm();
        StmRef<Integer> mine = stm.newRef("mine", 10);
        StmRef<Integer> theirs = stm.newRef("theirs", 0);

        stm.atomically(tx -> { tx.write(theirs, 42); return null; });

        assertThatThrownBy(() -> stm.atomically(tx -> {
            tx.write(mine, 99);
            tx.write(theirs, 100);
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        assertThat(mine.peek()).isEqualTo(10);
        assertThat(theirs.peek()).isEqualTo(42);
        assertThat(stm.stats().failures()).isEqualTo(1);
    }

    @Test
    void statisticsTrackTransactionsCommitsRetriesAndAverage() throws Exception {
        Stm stm = new Stm();
        StmRef<Integer> ref = stm.newRef("x", 0);

        stm.atomically(tx -> { tx.write(ref, 1); return null; });
        stm.atomically(tx -> { tx.write(ref, 2); return null; });
        stm.readOnly(tx -> { tx.read(ref); return null; });

        CyclicBarrier attemptStarted = new CyclicBarrier(2);
        CyclicBarrier interferenceDone = new CyclicBarrier(2);
        AtomicInteger attempts = new AtomicInteger();
        Thread interferer = new Thread(() -> {
            await(attemptStarted);
            stm.atomically(tx -> { tx.write(ref, 50); return null; });
            await(interferenceDone);
        });
        interferer.start();
        stm.atomically(tx -> {
            int value = tx.read(ref);
            if (attempts.getAndIncrement() == 0) {
                await(attemptStarted);
                await(interferenceDone);
            }
            tx.write(ref, value + 1);
            return null;
        });
        interferer.join(5_000);

        StmStats stats = stm.stats();
        assertThat(stats.transactions()).isEqualTo(5);
        assertThat(stats.commits()).isEqualTo(5);
        assertThat(stats.failures()).isZero();
        assertThat(stats.conflictRetries()).isEqualTo(1);
        assertThat(stats.averageRetries()).isEqualTo(0.2);
    }

    /**
     * A thread that, on every attempt of the transaction under test, commits a
     * competing write to {@code ref} between the victim's read and its commit.
     */
    private static Thread interferer(Stm stm, StmRef<Integer> ref, CyclicBarrier attemptStarted,
            CyclicBarrier interferenceDone, AtomicBoolean done) {
        return new Thread(() -> {
            while (!done.get()) {
                try {
                    attemptStarted.await(300, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    continue;
                } catch (InterruptedException | BrokenBarrierException e) {
                    return;
                }
                stm.atomically(tx -> { tx.write(ref, tx.read(ref) + 1); return null; });
                try {
                    interferenceDone.await(300, TimeUnit.MILLISECONDS);
                } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                    return;
                }
            }
        });
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (BrokenBarrierException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }
}
