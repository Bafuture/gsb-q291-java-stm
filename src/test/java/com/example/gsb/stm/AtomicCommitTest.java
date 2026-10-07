package com.example.gsb.stm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AtomicCommitTest {

    @Test
    void commitsMultiRefUpdateAtomically() {
        Stm stm = new Stm();
        StmRef<Integer> alice = stm.newRef("alice", 100);
        StmRef<Integer> bob = stm.newRef("bob", 50);

        stm.atomic(() -> {
            int amount = 30;
            alice.set(alice.get() - amount);
            bob.set(bob.get() + amount);
        });

        assertThat(stm.atomic(alice::get)).isEqualTo(70);
        assertThat(stm.atomic(bob::get)).isEqualTo(80);
        assertThat(stm.atomic(() -> alice.get() + bob.get())).isEqualTo(150);
    }

    @Test
    void jointCommitAssignsOneVersionToEveryWrittenRef() {
        Stm stm = new Stm();
        StmRef<Integer> left = stm.newRef("left", 1);
        StmRef<Integer> right = stm.newRef("right", 2);

        stm.atomic(() -> {
            left.set(10);
            right.set(20);
        });

        assertThat(left.currentVersion()).isEqualTo(right.currentVersion());
    }

    @Test
    void readYourWritesWithinTransaction() {
        Stm stm = new Stm();
        StmRef<String> greeting = stm.newRef("greeting", "hello");

        String seen = stm.atomic(() -> {
            greeting.set("world");
            return greeting.get();
        });

        assertThat(seen).isEqualTo("world");
        assertThat(stm.atomic(greeting::get)).isEqualTo("world");
    }

    @Test
    void concurrentIncrementWorkloadEndsUpExact() throws Exception {
        Stm stm = new Stm();
        StmRef<Integer> counter = stm.newRef("counter", 0);
        int threads = 8;
        int incrementsPerThread = 500;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < incrementsPerThread; i++) {
                    stm.atomic(() -> counter.set(counter.get() + 1));
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(stm.atomic(counter::get)).isEqualTo(threads * incrementsPerThread);
        assertThat(stm.stats().commits()).isEqualTo(threads * incrementsPerThread + 1L);
    }

    @Test
    void blockThrowingRollsBackBufferedWritesAndKeepsOthersCommittedChanges() {
        Stm stm = new Stm();
        StmRef<Integer> committed = stm.newRef("committed", 1);
        StmRef<Integer> tentative = stm.newRef("tentative", 10);

        stm.atomic(() -> committed.set(42));

        assertThatThrownBy(() -> stm.atomic(() -> {
            tentative.set(99);
            committed.set(99);
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        assertThat(stm.atomic(committed::get)).isEqualTo(42);
        assertThat(stm.atomic(tentative::get)).isEqualTo(10);
    }

    @Test
    void refAccessOutsideTransactionIsRejected() {
        Stm stm = new Stm();
        StmRef<Integer> ref = stm.newRef("orphan", 0);

        assertThatThrownBy(ref::get).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active transaction");
        assertThatThrownBy(() -> ref.set(1)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active transaction");
    }
}
