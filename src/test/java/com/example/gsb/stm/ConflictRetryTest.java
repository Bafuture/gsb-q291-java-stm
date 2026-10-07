package com.example.gsb.stm;

import static com.example.gsb.stm.StmTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConflictRetryTest {

    @Test
    void retriesWholeBlockAfterConflictAndSeesTheNewValue() throws Exception {
        Stm stm = new Stm(new StmConfig(10, Duration.ofSeconds(5)));
        StmRef<Integer> counter = stm.newRef("counter", 0);

        CountDownLatch firstAttemptHasRead = new CountDownLatch(1);
        CountDownLatch interfererCommitted = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Integer> outcome = pool.submit(() -> stm.atomic(() -> {
            int value = counter.get();
            if (attempts.getAndIncrement() == 0) {
                firstAttemptHasRead.countDown();
                await(interfererCommitted);
            }
            return value;
        }));

        await(firstAttemptHasRead);
        long interfererTx = stm.atomic(() -> {
            counter.set(99);
            return stm.currentTransactionId();
        });
        interfererCommitted.countDown();

        assertThat(outcome.get(10, TimeUnit.SECONDS)).isEqualTo(99);
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(interfererTx).isPositive();
        pool.shutdownNow();

        StmStats stats = stm.stats();
        assertThat(stats.transactions()).isEqualTo(2);
        assertThat(stats.commits()).isEqualTo(2);
        assertThat(stats.conflictRetries()).isEqualTo(1);
    }

    @Test
    void blindWriteWithoutReadDoesNotConflict() {
        Stm stm = new Stm(new StmConfig(10, Duration.ofSeconds(5)));
        StmRef<Integer> ref = stm.newRef("cell", 0);

        CountDownLatch writerStarted = new CountDownLatch(1);
        CountDownLatch interfererCommitted = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> done = pool.submit(() -> {
            stm.atomic(() -> {
                ref.set(7);
                writerStarted.countDown();
                await(interfererCommitted);
            });
            return null;
        });

        await(writerStarted);
        stm.atomic(() -> ref.set(42));
        interfererCommitted.countDown();

        assertThat(done).succeedsWithin(Duration.ofSeconds(10));
        assertThat(stm.atomic(ref::get)).isEqualTo(7);
        assertThat(stm.stats().conflictRetries()).isZero();
        pool.shutdownNow();
    }
}
