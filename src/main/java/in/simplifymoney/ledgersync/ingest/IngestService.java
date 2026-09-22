package in.simplifymoney.ledgersync.ingest;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;

/**
 * Reads a corpus of raw messages, deduplicates them into real transactions,
 * assigns categories, and saves to the ledger.
 *
 * --- Deduplication ---
 * One real transaction can arrive as multiple messages:
 *   - The bank SMS + the bank email for the same event
 *   - The same SMS re-uploaded on a second device sync (different message_id,
 *     same body / same bank timestamp)
 *
 * The deduplication key is: (accountLast4, direction, amount, occurredAt).
 * Two messages with the same key are evidence of the same real transaction.
 * All their message IDs are merged into sourceMessageIds (sorted).
 *
 * --- Category ---
 * TRANSFER: one leg of the user moving money between their own accounts.
 *           Detected by finding a matching debit+credit pair across the known
 *           accounts within a 5-minute window with the same amount.
 *           "NEFT INWARD SELF" credits are also transfers (the bank labels them).
 * MICRO:    a UPI debit of ₹100 or less.
 * INCOME:   any other credit.
 * SPEND:    everything else (debit > ₹100, or non-UPI debit ≤ ₹100).
 */
public final class IngestService {

    /** How close in time two legs of a transfer can be. */
    private static final Duration TRANSFER_WINDOW = Duration.ofMinutes(5);

    private final Parsers parsers;
    private final LedgerStore store;
    /** Optional sink for balance checkpoints (account, time, balance, msgId). */
    private final Consumer<ParsedTxn> checkpointSink;

    public IngestService(Parsers parsers, LedgerStore store) {
        this(parsers, store, null);
    }

    public IngestService(Parsers parsers, LedgerStore store,
                         Consumer<ParsedTxn> checkpointSink) {
        this.parsers = parsers;
        this.store = store;
        this.checkpointSink = checkpointSink;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);
        int skipped = 0;

        // ── Step 1: parse every message ─────────────────────────────────────
        // Key: (account, direction, amount, occurredAt) → accumulated evidence
        Map<TxnKey, Evidence> grouped = new LinkedHashMap<>();

        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);
            if (p.isEmpty()) {
                skipped++;
                continue;
            }
            ParsedTxn txn = p.get();
            TxnKey key = new TxnKey(
                    txn.accountLast4(), txn.direction(),
                    txn.amount(), txn.occurredAt());
            grouped.computeIfAbsent(key, k -> new Evidence(txn))
                   .addMessageId(txn.sourceMessageId());

            // Save balance checkpoint if the message carried a stated balance.
            if (checkpointSink != null && txn.statedBalance() != null) {
                checkpointSink.accept(txn);
            }
        }

        // ── Step 2: build candidate NormalizedTxn list (no category yet) ────
        List<NormalizedTxn> candidates = new ArrayList<>();
        for (Map.Entry<TxnKey, Evidence> e : grouped.entrySet()) {
            TxnKey k = e.getKey();
            Evidence ev = e.getValue();
            Category cat = preliminaryCategory(k.direction(), k.amount(),
                    ev.merchant());
            List<String> ids = new ArrayList<>(ev.messageIds());
            Collections.sort(ids);
            candidates.add(new NormalizedTxn(
                    k.account(), k.occurredAt(), k.direction(), k.amount(),
                    cat, ev.merchant(), ids));
        }

        // ── Step 3: reclassify transfers ─────────────────────────────────────
        List<NormalizedTxn> ledger = classifyTransfers(candidates);

        // ── Step 4: persist ──────────────────────────────────────────────────
        for (NormalizedTxn t : ledger) {
            store.save(t);
        }

        int written = ledger.size();
        return new Stats(messages.size(), written, skipped);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Preliminary category before transfer detection.
     * MICRO = UPI debit ≤ 100. INCOME = credit. SPEND = everything else.
     */
    private static Category preliminaryCategory(Direction dir, BigDecimal amount,
                                                 String merchant) {
        if (dir == Direction.CREDIT) return Category.INCOME;
        // Debit path: MICRO = any UPI debit of ₹100 or less.
        // "UPI/" is the standard prefix, but "UPI " (with space) also appears
        // in some banks' message formats (e.g. "UPI MANDATE VERIFY").
        boolean isUpi = merchant != null
                && merchant.toUpperCase().startsWith("UPI");
        if (isUpi && amount.compareTo(new BigDecimal("100.00")) <= 0) {
            return Category.MICRO;
        }
        return Category.SPEND;
    }

    /**
     * Find matching debit/credit pairs across different accounts within the
     * transfer window and mark both legs as TRANSFER.
     *
     * Rules:
     *  1. Same amount, one is DEBIT, one is CREDIT, on DIFFERENT accounts,
     *     and the timestamps are within TRANSFER_WINDOW.
     *  2. OR the merchant explicitly contains "NEFT INWARD SELF".
     *
     * We do a greedy O(n²) match — the ledger is small (hundreds of txns).
     */
    private static List<NormalizedTxn> classifyTransfers(List<NormalizedTxn> candidates) {
        boolean[] isTransfer = new boolean[candidates.size()];

        // Paired amount+time matching across accounts.
        // Two conditions must both hold:
        //  1. One is DEBIT, one is CREDIT, on DIFFERENT accounts, same amount.
        //  2. Timestamps are within TRANSFER_WINDOW.
        //  3. At least one merchant starts with IMPS/ or NEFT (plausibility guard
        //     so that coincidental same-amount/same-time debits are not misclassified).
        for (int i = 0; i < candidates.size(); i++) {
            NormalizedTxn a = candidates.get(i);
            for (int j = i + 1; j < candidates.size(); j++) {
                NormalizedTxn b = candidates.get(j);

                if (a.direction() == b.direction()) continue;
                if (!a.amount().equals(b.amount())) continue;
                if (a.accountLast4().equals(b.accountLast4())) continue;

                long secondsApart = Math.abs(
                        Duration.between(a.occurredAt(), b.occurredAt()).getSeconds());
                if (secondsApart > TRANSFER_WINDOW.getSeconds()) continue;

                if (looksLikeTransferMerchant(a.merchant())
                        || looksLikeTransferMerchant(b.merchant())) {
                    isTransfer[i] = true;
                    isTransfer[j] = true;
                }
            }
        }

        // Rebuild with corrected categories
        List<NormalizedTxn> result = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            NormalizedTxn t = candidates.get(i);
            if (isTransfer[i] && t.category() != Category.TRANSFER) {
                t = new NormalizedTxn(t.accountLast4(), t.occurredAt(),
                        t.direction(), t.amount(), Category.TRANSFER,
                        t.merchant(), t.sourceMessageIds());
            }
            result.add(t);
        }
        return result;
    }

    private static boolean looksLikeTransferMerchant(String merchant) {
        if (merchant == null) return false;
        String upper = merchant.toUpperCase();
        return upper.startsWith("IMPS/") || upper.startsWith("NEFT");
    }

    // ── Corpus reader ────────────────────────────────────────────────────────

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    // ── Inner types ──────────────────────────────────────────────────────────

    private record TxnKey(
            String account,
            Direction direction,
            BigDecimal amount,
            OffsetDateTime occurredAt) {}

    private static final class Evidence {
        private final ParsedTxn representative;
        private final Set<String> messageIds = new TreeSet<>();

        Evidence(ParsedTxn first) {
            this.representative = first;
            this.messageIds.add(first.sourceMessageId());
        }

        void addMessageId(String id) { messageIds.add(id); }

        String merchant()        { return representative.merchant(); }
        Set<String> messageIds() { return messageIds; }
    }

    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {}
}
