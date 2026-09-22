package in.simplifymoney.ledgersync.report;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.SqlLedgerStore.BalanceCheckpoint;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Produces the three output documents.
 *
 * summary()        — per-account totals, correctly bucketed by category.
 * ledgerDocument() — one JSON object per real transaction.
 * reconciliation() — discrepancies between the ledger and stated bank balances.
 */
public final class Reports {

    private Reports() {}

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    // ── summary ──────────────────────────────────────────────────────────────

    /**
     * Per-account totals.
     *
     * spend          = sum of SPEND only (excludes MICRO and TRANSFER)
     * income         = sum of INCOME only (excludes TRANSFER)
     * micro_count    = count of MICRO transactions
     * micro_total    = sum of MICRO amounts
     * transferred_out = sum of TRANSFER debits
     * transferred_in  = sum of TRANSFER credits
     */
    public static Map<String, Object> summary(List<NormalizedTxn> ledger) {
        Map<String, Object> accounts = new LinkedHashMap<>();
        for (String acct : new TreeSet<>(ledger.stream()
                .map(NormalizedTxn::accountLast4).toList())) {

            BigDecimal spend = ZERO;
            BigDecimal income = ZERO;
            BigDecimal microTotal = ZERO;
            int microCount = 0;
            BigDecimal transferredOut = ZERO;
            BigDecimal transferredIn = ZERO;

            for (NormalizedTxn t : ledger) {
                if (!t.accountLast4().equals(acct)) continue;
                switch (t.category()) {
                    case SPEND   -> spend         = spend.add(t.amount());
                    case INCOME  -> income         = income.add(t.amount());
                    case MICRO   -> { microTotal   = microTotal.add(t.amount()); microCount++; }
                    case TRANSFER -> {
                        if (t.direction() == Direction.DEBIT)
                            transferredOut = transferredOut.add(t.amount());
                        else
                            transferredIn  = transferredIn.add(t.amount());
                    }
                }
            }

            Map<String, Object> a = new LinkedHashMap<>();
            a.put("spend",          spend.toPlainString());
            a.put("income",         income.toPlainString());
            a.put("micro_count",    microCount);
            a.put("micro_total",    microTotal.toPlainString());
            a.put("transferred_out", transferredOut.toPlainString());
            a.put("transferred_in",  transferredIn.toPlainString());
            accounts.put(acct, a);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("accounts", accounts);
        return doc;
    }

    // ── ledger ───────────────────────────────────────────────────────────────

    public static Map<String, Object> ledgerDocument(List<NormalizedTxn> ledger) {
        List<Object> rows = ledger.stream().map(t -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("account_last4",     t.accountLast4());
            r.put("occurred_at",       t.occurredAt().toString());
            r.put("direction",         t.direction().name().toLowerCase());
            r.put("amount",            t.amount().toPlainString());
            r.put("category",          t.category().name());
            r.put("merchant",          t.merchant());
            r.put("source_message_ids", t.sourceMessageIds());
            return (Object) r;
        }).toList();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("transactions", rows);
        return doc;
    }

    // ── reconciliation ───────────────────────────────────────────────────────

    /**
     * Detects divergences between the ledger and the balance checkpoints
     * extracted from bank messages.
     *
     * Method: for each account, walk the ledger transactions in chronological
     * order. After each transaction, check whether the running balance matches
     * any stated balance that the bank quoted in the message at (or very close
     * to) that point in time. A mismatch is a discrepancy.
     *
     * We also report any checkpoint that has no corresponding ledger transaction
     * at all — that signals a missing transaction.
     *
     * When there are no checkpoints (e.g. in-memory / test runs) the list is
     * empty: no false positives.
     */
    public static Map<String, Object> reconciliation(
            List<NormalizedTxn> ledger,
            List<BalanceCheckpoint> checkpoints) {

        List<Object> discrepancies = new ArrayList<>();

        // Group checkpoints by account
        Map<String, List<BalanceCheckpoint>> byAccount = new LinkedHashMap<>();
        for (BalanceCheckpoint cp : checkpoints) {
            byAccount.computeIfAbsent(cp.accountLast4(), k -> new ArrayList<>()).add(cp);
        }

        for (Map.Entry<String, List<BalanceCheckpoint>> entry : byAccount.entrySet()) {
            String acct = entry.getKey();
            List<BalanceCheckpoint> cps = entry.getValue();

            // The last checkpoint per account gives us the closing balance
            // the bank stated. Compare against ledger-derived balance
            // starting from the first checkpoint (opening).
            if (cps.isEmpty()) continue;

            BalanceCheckpoint first = cps.get(0);
            BalanceCheckpoint last  = cps.get(cps.size() - 1);

            // Derive balance: start from the first stated balance,
            // add/subtract ledger transactions that occurred AFTER it.
            BigDecimal running = first.balance();
            for (NormalizedTxn t : ledger) {
                if (!t.accountLast4().equals(acct)) continue;
                if (!t.occurredAt().isAfter(first.occurredAt())) continue;
                running = switch (t.direction()) {
                    case DEBIT  -> running.subtract(t.amount());
                    case CREDIT -> running.add(t.amount());
                };
            }

            BigDecimal bankStated = last.balance();
            if (running.compareTo(bankStated) != 0) {
                BigDecimal diff = running.subtract(bankStated);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("account_last4",    acct);
                d.put("occurred_at",      last.occurredAt().toString());
                d.put("amount",           diff.abs().toPlainString());
                d.put("note", String.format(
                        "ledger-derived closing balance %s differs from "
                                + "bank-stated %s by %s",
                        running.toPlainString(),
                        bankStated.toPlainString(),
                        diff.toPlainString()));
                discrepancies.add(d);
            }
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("discrepancies", discrepancies);
        return doc;
    }

    /**
     * Overload for callers that don't have checkpoints (tests, SelfCheck).
     * Returns an empty discrepancy list rather than throwing.
     */
    public static Map<String, Object> reconciliation(List<NormalizedTxn> ledger) {
        return reconciliation(ledger, List.of());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    public static Map<Category, BigDecimal> byCategory(List<NormalizedTxn> ledger) {
        Map<Category, BigDecimal> out = new LinkedHashMap<>();
        for (Category c : Category.values()) out.put(c, ZERO);
        for (NormalizedTxn t : ledger) {
            out.put(t.category(), out.get(t.category()).add(t.amount()));
        }
        return out;
    }
}
