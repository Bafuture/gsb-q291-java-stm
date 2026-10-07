package com.example.gsb.stm;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single transaction attempt. Every read and write performed inside an
 * atomic block is recorded here:
 *
 * <ul>
 *   <li>read set: ref -> version observed at first read;</li>
 *   <li>write set: ref -> pending value, keyed by ref so that repeated writes
 *       to the same ref keep only the last one.</li>
 * </ul>
 *
 * Nothing in the write set is visible to other transactions until commit.
 */
public final class Transaction {

    private final long id;
    private final boolean readOnly;

    private final Map<StmRef<?>, Long> readSet = new HashMap<>();
    private final Map<StmRef<?>, Object> writeSet = new LinkedHashMap<>();

    Transaction(long id, boolean readOnly) {
        this.id = id;
        this.readOnly = readOnly;
    }

    public long id() {
        return id;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     * Reads a ref transactionally. Writes made earlier in this same
     * transaction are visible (read-your-writes); the first read of a ref
     * records its version in the read set.
     */
    @SuppressWarnings("unchecked")
    public <T> T read(StmRef<T> ref) {
        Object pending = writeSet.get(ref);
        if (pending != null || writeSet.containsKey(ref)) {
            return (T) pending;
        }
        StmRef.Snapshot<T> snapshot = ref.snapshot();
        readSet.putIfAbsent(ref, snapshot.version());
        return snapshot.value();
    }

    /**
     * Stages a write. Only the last value written to a given ref within this
     * transaction is committed.
     */
    public <T> void write(StmRef<T> ref, T value) {
        if (readOnly) {
            throw new IllegalStateException(
                    "transaction #" + id + " is read-only and cannot write ref '" + ref.name() + "'");
        }
        writeSet.put(ref, value);
    }

    Map<StmRef<?>, Long> readSet() {
        return Collections.unmodifiableMap(readSet);
    }

    Map<StmRef<?>, Object> writeSet() {
        return writeSet;
    }
}
