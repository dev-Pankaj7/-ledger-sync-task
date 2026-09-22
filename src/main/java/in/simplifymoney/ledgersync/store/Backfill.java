package in.simplifymoney.ledgersync.store;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import in.simplifymoney.ledgersync.model.NormalizedTxn;

/**
 * Moves everything already in the SQL store into the document store.
 *
 * NOT IMPLEMENTED - this is yours.
 *
 * Two things to know before you start:
 *  - the SQL store is not clean. It has been running without a uniqueness
 *    guarantee for a long time
 *  - this will be run more than once, including after a partial failure
 */
public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(SqlLedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    /**
     * Backfill transactions from SQL to document store.
     *
     * Idempotency strategy:
     * - SQL has NO uniqueness guarantee (duplicate rows exist in V2__seed.sql)
     * - DocumentStore.save() uses upsert on (account, occurredAt, direction, amount)
     * - Duplicate SQL rows map to the same document → only one copy saved
     * - Re-running backfill re-writes same documents → no duplicates created
     *
     * Safe to run multiple times, including after partial failure.
     */
    public Result run() {
        List<NormalizedTxn> sqlTransactions = source.all();
        long read = sqlTransactions.size();
        long written = 0;
        long skipped = 0;

        // Track unique transactions using the same key as DocumentStore
        Set<String> seen = new HashSet<>();

        for (NormalizedTxn txn : sqlTransactions) {
            // Create unique key: (account, occurredAt, direction, amount)
            String key = txn.accountLast4() + "|"
                    + txn.occurredAt().toString() + "|"
                    + txn.direction().name() + "|"
                    + txn.amount().toPlainString();

            if (seen.contains(key)) {
                // Duplicate in SQL - skip but still log it
                skipped++;
                continue;
            }

            seen.add(key);
            target.save(txn);
            written++;
        }

        return new Result(read, written, skipped);
    }

    public record Result(long read, long written, long skipped) {}
}
