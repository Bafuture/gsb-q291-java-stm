package com.example.gsb.stm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A single transaction attempt: its read set (ref -> version read) and write set
 * (ref -> buffered value, insertion order). Package-private; users never see it.
 */
final class Transaction {

    private final Stm owner;
    private final long id;
    private final boolean readOnly;
    private final Map<StmRef<?>, Long> readSet = new HashMap<>();
    private final Map<StmRef<?>, Object> writeSet = new LinkedHashMap<>();

    Transaction(Stm owner, long id, boolean readOnly) {
        this.owner = owner;
        this.id = id;
        this.readOnly = readOnly;
    }

    Stm owner() {
        return owner;
    }

    long id() {
        return id;
    }

    boolean isReadOnly() {
        return readOnly;
    }

    Map<StmRef<?>, Object> writeSetForTest() {
        return writeSet;
    }

    @SuppressWarnings("unchecked")
    <T> T read(StmRef<T> ref) {
        if (writeSet.containsKey(ref)) {
            return (T) writeSet.get(ref);
        }
        StmRef.VersionedValue<T> current = ref.current();
        readSet.putIfAbsent(ref, current.version());
        return current.value();
    }

    <T> void write(StmRef<T> ref, T value) {
        if (readOnly) {
            throw new IllegalStateException(
                    "transaction " + id + " is read-only; cannot write ref '" + ref.name() + "'");
        }
        writeSet.put(ref, value);
    }

    /**
     * Commits atomically: lock the whole write set (sorted by ref id to avoid
     * deadlock), re-validate the read set, then publish all buffered values
     * under one shared version. Read-only transactions only validate.
     */
    void commit() {
        if (writeSet.isEmpty()) {
            validateReadSet();
            return;
        }
        List<StmRef<?>> locked = lockWriteSet();
        try {
            validateReadSet();
            long commitVersion = owner.nextVersion();
            for (StmRef<?> ref : locked) {
                ref.commitValue(commitVersion, writeSet.get(ref), id);
            }
        } finally {
            for (int i = locked.size() - 1; i >= 0; i--) {
                locked.get(i).unlock();
            }
        }
    }

    private List<StmRef<?>> lockWriteSet() {
        List<StmRef<?>> refs = new ArrayList<>(writeSet.keySet());
        refs.sort(Comparator.comparingLong(StmRef::internalId));
        for (StmRef<?> ref : refs) {
            ref.lock();
        }
        return refs;
    }

    private void validateReadSet() {
        for (Map.Entry<StmRef<?>, Long> entry : readSet.entrySet()) {
            StmRef<?> ref = entry.getKey();
            long expectedVersion = entry.getValue();
            StmRef.VersionedValue<?> current = ref.current();
            if (current.version() != expectedVersion) {
                throw new StmConflictException(id, ref.name(), expectedVersion,
                        current.version(), current.writerTxId());
            }
        }
    }
}
