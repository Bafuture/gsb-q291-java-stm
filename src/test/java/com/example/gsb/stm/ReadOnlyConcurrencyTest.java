package com.example.gsb.stm;

import static com.example.gsb.stm.StmTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ReadOnlyConcurrencyTest {

    @Test
    void twoReadOnlyTransactionsOverlapAndBothCommitWithoutAnyRetry() throws Exception {
        Stm stm = new Stm();
        StmRef<Integer> first = stm.newRef("first", 1);
        StmRef<Integer> second = stm.newRef("second", 2);
        CyclicBarrier insideTransaction = new CyclicBarrier(2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Integer> readerOne = pool.submit(() -> stm.atomicReadOnly(() -> {
            int sum = first.get() + second.get();
            await(insideTransaction);
            return sum;
        }));
        Future<Integer> readerTwo = pool.submit(() -> stm.atomicReadOnly(() -> {
            int sum = first.get() + second.get();
            await(insideTransaction);
            return sum;
        }));

        assertThat(readerOne.get(10, TimeUnit.SECONDS)).isEqualTo(3);
        assertThat(readerTwo.get(10, TimeUnit.SECONDS)).isEqualTo(3);
        pool.shutdownNow();

        StmStats stats = stm.stats();
        assertThat(stats.transactions()).isEqualTo(2);
        assertThat(stats.commits()).isEqualTo(2);
        assertThat(stats.conflictRetries()).isZero();
    }

    @Test
    void readOnlyTransactionsNeverBlockConcurrentWriters() throws Exception {
        Stm stm = new Stm();
        StmRef<Integer> ref = stm.newRef("n", 0);
        CyclicBarrier barrier = new CyclicBarrier(2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Integer> reader = pool.submit(() -> stm.atomicReadOnly(() -> {
            int value = ref.get();
            await(barrier);
            return value;
        }));
        Future<Integer> writer = pool.submit(() -> {
            await(barrier);
            return stm.atomic(() -> {
                ref.set(1);
                return ref.get();
            });
        });

        int readerValue = reader.get(10, TimeUnit.SECONDS);
        assertThat(readerValue).isIn(0, 1);
        assertThat(writer.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        pool.shutdownNow();
    }

    @Test
    void readOnlyTransactionRejectsWrites() {
        Stm stm = new Stm();
        StmRef<Integer> ref = stm.newRef("locked", 5);

        assertThatThrownBy(() -> stm.atomicReadOnly(() -> ref.set(6)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read-only")
                .hasMessageContaining("locked");

        assertThat(stm.atomic(ref::get)).isEqualTo(5);
    }

}
