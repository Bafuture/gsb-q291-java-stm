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

class RetryLimitTest {

    @Test
    void givesUpAfterMaxRetriesAndNamesTheRefAndTheWriterTransaction() throws Exception {
        Stm stm = new Stm(new StmConfig(2, Duration.ofSeconds(30)));
        StmRef<Integer> balance = stm.newRef("balance", 0);

        int expectedAttempts = 3;
        CountDownLatch[] attemptHasRead = latches(expectedAttempts);
        CountDownLatch[] interferenceCommitted = latches(expectedAttempts);
        long[] interfererTxIds = new long[expectedAttempts];
        AtomicInteger attempt = new AtomicInteger();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<StmRetryExhaustedException> victim = pool.submit(() -> {
            try {
                stm.atomic(() -> {
                    balance.get();
                    int round = attempt.getAndIncrement();
                    attemptHasRead[round].countDown();
                    await(interferenceCommitted[round]);
                    return null;
                });
                throw new AssertionError("expected StmRetryExhaustedException");
            } catch (StmRetryExhaustedException exhausted) {
                return exhausted;
            }
        });

        for (int round = 0; round < expectedAttempts; round++) {
            await(attemptHasRead[round]);
            final int value = round + 1;
            interfererTxIds[round] = stm.atomic(() -> {
                balance.set(value);
                return stm.currentTransactionId();
            });
            interferenceCommitted[round].countDown();
        }

        StmRetryExhaustedException failure = victim.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(attempt.get()).isEqualTo(expectedAttempts);
        assertThat(failure.retries()).isEqualTo(2);
        assertThat(failure.getMessage())
                .contains("balance")
                .contains(String.valueOf(interfererTxIds[2]));
        assertThat(failure.lastConflict().refName()).isEqualTo("balance");
        assertThat(failure.lastConflict().writerTxId()).isEqualTo(interfererTxIds[2]);
        assertThat(failure.lastConflict().actualVersion())
                .isGreaterThan(failure.lastConflict().expectedVersion());
    }

    @Test
    void givesUpWhenTotalDurationIsExceeded() throws Exception {
        Stm stm = new Stm(new StmConfig(1_000_000, Duration.ofMillis(1)));
        StmRef<Integer> balance = stm.newRef("balance", 0);

        int maxRounds = 20;
        CountDownLatch[] attemptHasRead = latches(maxRounds);
        CountDownLatch[] interferenceCommitted = latches(maxRounds);
        AtomicInteger attempt = new AtomicInteger();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<StmRetryExhaustedException> victim = pool.submit(() -> {
            try {
                stm.atomic(() -> {
                    balance.get();
                    int round = attempt.getAndIncrement();
                    attemptHasRead[round].countDown();
                    await(interferenceCommitted[round]);
                    return null;
                });
                throw new AssertionError("expected StmRetryExhaustedException");
            } catch (StmRetryExhaustedException exhausted) {
                return exhausted;
            }
        });

        int round = 0;
        while (round < maxRounds && !victim.isDone()) {
            while (!victim.isDone() && !attemptHasRead[round].await(50, TimeUnit.MILLISECONDS)) {
                // poll: the victim may exhaust its time budget at any moment
            }
            if (victim.isDone()) {
                break;
            }
            final int value = round + 1;
            stm.atomic(() -> balance.set(value));
            interferenceCommitted[round].countDown();
            round++;
        }

        StmRetryExhaustedException failure = victim.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(failure.getMessage()).contains("balance").contains("gave up");
        assertThat(failure.elapsed()).isGreaterThanOrEqualTo(Duration.ofMillis(1));
    }

    @Test
    void zeroRetriesFailsOnFirstConflict() throws Exception {
        Stm stm = new Stm(new StmConfig(0, Duration.ofSeconds(30)));
        StmRef<Integer> ref = stm.newRef("hot", 0);

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

        StmRetryExhaustedException failure = victim.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(failure.retries()).isZero();
        assertThat(failure.lastConflict().refName()).isEqualTo("hot");
    }

    private static CountDownLatch[] latches(int count) {
        CountDownLatch[] latches = new CountDownLatch[count];
        for (int i = 0; i < count; i++) {
            latches[i] = new CountDownLatch(1);
        }
        return latches;
    }
}
