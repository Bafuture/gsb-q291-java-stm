package com.example.gsb.stm;

import static com.example.gsb.stm.StmTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StatsTest {

    @Test
    void countsTransactionsCommitsAndZeroRetriesOnCleanRun() {
        Stm stm = new Stm();
        StmRef<Integer> ref = stm.newRef("n", 0);

        stm.atomic(() -> ref.set(1));
        stm.atomic(() -> ref.set(2));
        int value = stm.atomic(ref::get);

        assertThat(value).isEqualTo(2);
        StmStats stats = stm.stats();
        assertThat(stats.transactions()).isEqualTo(3);
        assertThat(stats.commits()).isEqualTo(3);
        assertThat(stats.conflictRetries()).isZero();
        assertThat(stats.averageRetriesPerTransaction()).isZero();
    }

    @Test
    void countsConflictRetriesAndComputesAverageRounds() throws Exception {
        Stm stm = new Stm(new StmConfig(10, Duration.ofSeconds(30)));
        StmRef<Integer> ref = stm.newRef("n", 0);

        stm.atomic(() -> ref.set(0));

        int conflictingRounds = 2;
        CountDownLatch[] attemptHasRead = new CountDownLatch[conflictingRounds];
        CountDownLatch[] interferenceCommitted = new CountDownLatch[conflictingRounds];
        for (int i = 0; i < conflictingRounds; i++) {
            attemptHasRead[i] = new CountDownLatch(1);
            interferenceCommitted[i] = new CountDownLatch(1);
        }
        AtomicInteger attempt = new AtomicInteger();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Integer> victim = pool.submit(() -> stm.atomic(() -> {
            int value = ref.get();
            int round = attempt.getAndIncrement();
            if (round < conflictingRounds) {
                attemptHasRead[round].countDown();
                await(interferenceCommitted[round]);
            }
            return value;
        }));

        for (int round = 0; round < conflictingRounds; round++) {
            await(attemptHasRead[round]);
            final int value = round + 1;
            stm.atomic(() -> ref.set(value));
            interferenceCommitted[round].countDown();
        }

        assertThat(victim.get(10, TimeUnit.SECONDS)).isEqualTo(2);
        pool.shutdownNow();

        StmStats stats = stm.stats();
        assertThat(stats.transactions()).isEqualTo(4);
        assertThat(stats.commits()).isEqualTo(4);
        assertThat(stats.conflictRetries()).isEqualTo(2);
        assertThat(stats.averageRetriesPerTransaction()).isEqualTo(0.5, offset(1e-9));
    }

    @Test
    void failedTransactionCountsAsTransactionButNotAsCommit() throws Exception {
        Stm stm = new Stm(new StmConfig(0, Duration.ofSeconds(30)));
        StmRef<Integer> ref = stm.newRef("n", 0);

        CountDownLatch attemptHasRead = new CountDownLatch(1);
        CountDownLatch interferenceCommitted = new CountDownLatch(1);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<StmRetryExhaustedException> victim = pool.submit(() -> {
            try {
                stm.atomic(() -> {
                    ref.get();
                    attemptHasRead.countDown();
                    await(interferenceCommitted);
                    return null;
                });
                throw new AssertionError("expected StmRetryExhaustedException");
            } catch (StmRetryExhaustedException exhausted) {
                return exhausted;
            }
        });

        await(attemptHasRead);
        stm.atomic(() -> ref.set(1));
        interferenceCommitted.countDown();
        assertThat(victim.get(10, TimeUnit.SECONDS)).isNotNull();
        pool.shutdownNow();

        StmStats stats = stm.stats();
        assertThat(stats.transactions()).isEqualTo(2);
        assertThat(stats.commits()).isEqualTo(1);
        assertThat(stats.conflictRetries()).isZero();
        assertThat(stats.averageRetriesPerTransaction()).isZero();
    }
}
