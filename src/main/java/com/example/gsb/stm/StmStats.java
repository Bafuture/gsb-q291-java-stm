package com.example.gsb.stm;

/**
 * Immutable snapshot of STM runtime counters.
 *
 * @param transactions                     logical atomic blocks entered (successful or failed)
 * @param commits                          atomic blocks that committed
 * @param conflictRetries                  total conflict-driven retries
 * @param averageRetriesPerTransaction     {@code conflictRetries / transactions}
 */
public record StmStats(long transactions, long commits, long conflictRetries,
                       double averageRetriesPerTransaction) {
}
