package in.simplifymoney.ledgersync.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import in.simplifymoney.ledgersync.model.NormalizedTxn;

/**
 * Proves the two stores agree, and says precisely where they do not.
 *
 * We will run your checker against a document store we have deliberately
 * altered. It has to find what we changed and name it. A checker that only
 * compares row counts will not.
 */
public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    /**
     * Semantic consistency check between SQL and document stores.
     *
     * Strategy:
     * 1. Read all transactions from SQL (this is the source of truth after dedup)
     * 2. For each SQL transaction, look it up in document store by message IDs
     * 3. Compare field-by-field: account, timestamp, direction, amount, category, merchant
     * 4. Report any transaction present in SQL but missing in documents
     * 5. Report any field mismatch with exact values from both stores
     *
     * This is NOT a row-count comparison. It detects:
     * - Missing transactions
     * - Modified amounts
     * - Changed categories
     * - Altered timestamps
     * - Any field-level divergence
     */
    public List<Divergence> check() {
        List<Divergence> divergences = new ArrayList<>();
        List<NormalizedTxn> sqlTransactions = sql.all();

        // Deduplicate SQL first (SQL has no uniqueness guarantee)
        Map<String, NormalizedTxn> uniqueSql = new HashMap<>();
        for (NormalizedTxn txn : sqlTransactions) {
            String key = makeKey(txn);
            uniqueSql.putIfAbsent(key, txn);
        }

        // Check each unique SQL transaction against document store
        for (NormalizedTxn sqlTxn : uniqueSql.values()) {
            // Look up by first message ID (transactions always have at least one)
            String firstMessageId = sqlTxn.sourceMessageIds().get(0);
            Optional<NormalizedTxn> docTxnOpt = documents.byMessageId(firstMessageId);

            if (docTxnOpt.isEmpty()) {
                // Transaction exists in SQL but not in documents
                divergences.add(new Divergence(
                        "Missing transaction in document store",
                        formatTxn(sqlTxn),
                        "NOT FOUND"));
                continue;
            }

            NormalizedTxn docTxn = docTxnOpt.get();

            // Field-by-field comparison
            if (!sqlTxn.accountLast4().equals(docTxn.accountLast4())) {
                divergences.add(new Divergence(
                        "Account mismatch for " + formatTxnKey(sqlTxn),
                        "account=" + sqlTxn.accountLast4(),
                        "account=" + docTxn.accountLast4()));
            }

            if (!sqlTxn.occurredAt().equals(docTxn.occurredAt())) {
                divergences.add(new Divergence(
                        "Timestamp mismatch for " + formatTxnKey(sqlTxn),
                        "occurred_at=" + sqlTxn.occurredAt(),
                        "occurred_at=" + docTxn.occurredAt()));
            }

            if (!sqlTxn.direction().equals(docTxn.direction())) {
                divergences.add(new Divergence(
                        "Direction mismatch for " + formatTxnKey(sqlTxn),
                        "direction=" + sqlTxn.direction(),
                        "direction=" + docTxn.direction()));
            }

            if (sqlTxn.amount().compareTo(docTxn.amount()) != 0) {
                divergences.add(new Divergence(
                        "Amount mismatch for " + formatTxnKey(sqlTxn),
                        "amount=" + sqlTxn.amount().toPlainString(),
                        "amount=" + docTxn.amount().toPlainString()));
            }

            if (!sqlTxn.category().equals(docTxn.category())) {
                divergences.add(new Divergence(
                        "Category mismatch for " + formatTxnKey(sqlTxn),
                        "category=" + sqlTxn.category(),
                        "category=" + docTxn.category()));
            }

            if (!sqlTxn.merchant().equals(docTxn.merchant())) {
                divergences.add(new Divergence(
                        "Merchant mismatch for " + formatTxnKey(sqlTxn),
                        "merchant=" + sqlTxn.merchant(),
                        "merchant=" + docTxn.merchant()));
            }

            // Message IDs (sorted comparison)
            Set<String> sqlMsgIds = new HashSet<>(sqlTxn.sourceMessageIds());
            Set<String> docMsgIds = new HashSet<>(docTxn.sourceMessageIds());
            if (!sqlMsgIds.equals(docMsgIds)) {
                divergences.add(new Divergence(
                        "Message IDs mismatch for " + formatTxnKey(sqlTxn),
                        "messages=" + String.join(",", sqlTxn.sourceMessageIds()),
                        "messages=" + String.join(",", docTxn.sourceMessageIds())));
            }
        }

        return divergences;
    }

    private String makeKey(NormalizedTxn txn) {
        return txn.accountLast4() + "|"
                + txn.occurredAt().toString() + "|"
                + txn.direction().name() + "|"
                + txn.amount().toPlainString();
    }

    private String formatTxnKey(NormalizedTxn txn) {
        return String.format("%s %s %s on %s",
                txn.accountLast4(),
                txn.direction(),
                txn.amount().toPlainString(),
                txn.occurredAt());
    }

    private String formatTxn(NormalizedTxn txn) {
        return String.format("%s %s %s %s on %s via %s: %s",
                txn.accountLast4(),
                txn.direction(),
                txn.amount().toPlainString(),
                txn.category(),
                txn.occurredAt(),
                txn.merchant(),
                String.join(",", txn.sourceMessageIds()));
    }

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}
}
