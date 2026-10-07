package com.example.gsb.stm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WriteCoalescingTest {

    @Test
    void repeatedWritesKeepOnlyTheLastValue() {
        Stm stm = new Stm();
        StmRef<Integer> cell = stm.newRef("cell", 0);

        stm.atomic(() -> {
            cell.set(1);
            cell.set(2);
            cell.set(3);
        });

        assertThat(stm.atomic(cell::get)).isEqualTo(3);
    }

    @Test
    void repeatedWritesBumpTheVersionOnlyOnce() {
        Stm stm = new Stm();
        StmRef<Integer> cell = stm.newRef("cell", 0);
        long versionBefore = cell.currentVersion();

        stm.atomic(() -> {
            cell.set(1);
            cell.set(2);
            cell.set(3);
        });

        assertThat(cell.currentVersion()).isEqualTo(versionBefore + 1);
    }

    @Test
    void transactionLogHoldsASingleEntryPerRef() {
        Stm stm = new Stm();
        StmRef<Integer> cell = stm.newRef("cell", 0);

        stm.atomic(() -> {
            cell.set(1);
            cell.set(2);
            cell.set(3);
            assertThat(stm.currentTransaction().writeSetForTest()).hasSize(1);
        });
    }

    @Test
    void rolledBackWritesNeverTouchCommittedState() {
        Stm stm = new Stm();
        StmRef<Integer> ref = stm.newRef("stable", 10);
        long versionBefore = ref.currentVersion();

        try {
            stm.atomic(() -> {
                ref.set(99);
                ref.set(100);
                throw new IllegalStateException("abort");
            });
        } catch (IllegalStateException expected) {
            // rolled back
        }

        assertThat(stm.atomic(ref::get)).isEqualTo(10);
        assertThat(ref.currentVersion()).isEqualTo(versionBefore);
    }
}
