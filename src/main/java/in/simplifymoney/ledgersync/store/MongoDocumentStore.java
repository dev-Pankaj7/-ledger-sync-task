package in.simplifymoney.ledgersync.store;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * MongoDB implementation of DocumentStore.
 *
 * Document Model Design:
 * 
 * Each transaction is stored as a document with:
 * - Natural transaction fields (account, occurred_at, direction, amount, etc.)
 * - Computed fields for efficient querying:
 *   - accountMonth: "4821-2026-07" for range queries
 *   - occurredAtMillis: timestamp for sorting
 * - Denormalized message IDs array for reverse lookup
 *
 * Indexes:
 * 1. {accountMonth: 1, occurredAtMillis: -1} - Q1: account-month queries, newest first
 * 2. {account_last4: 1} - Q2: category totals aggregation
 * 3. {source_message_ids: 1} - Q3: message ID lookups (multikey index)
 *
 * Uniqueness:
 * - Compound unique index on (account_last4, occurred_at, direction, amount)
 * - Ensures idempotent saves and backfill
 */
public final class MongoDocumentStore implements DocumentStore, AutoCloseable {

    private final MongoClient client;
    private final MongoDatabase database;
    private final MongoCollection<Document> transactions;

    /**
     * Default constructor connects to MongoDB at localhost:27017.
     */
    public MongoDocumentStore() {
        this("mongodb://ledger:ledger@localhost:27017", "ledger");
    }

    /**
     * Constructor for testing or custom MongoDB URIs.
     */
    public MongoDocumentStore(String mongoUri, String databaseName) {
        try {
            this.client = MongoClients.create(mongoUri);
            this.database = client.getDatabase(databaseName);
            this.transactions = database.getCollection("transactions");
            ensureIndexes();
        } catch (MongoException e) {
            throw new IllegalStateException(
                    "Could not connect to MongoDB at " + mongoUri 
                    + ". Is MongoDB running? Try: docker compose up -d", e);
        }
    }

    /**
     * Create indexes for the three query patterns.
     * Idempotent - safe to call multiple times.
     */
    private void ensureIndexes() {
        try {
            // Q1: Account-month queries, newest first
            transactions.createIndex(
                    Indexes.compoundIndex(
                            Indexes.ascending("accountMonth"),
                            Indexes.descending("occurredAtMillis")),
                    new IndexOptions().name("idx_account_month_time"));

            // Q2: Category totals by account (simple index, aggregation scans by account)
            transactions.createIndex(
                    Indexes.ascending("account_last4"),
                    new IndexOptions().name("idx_account"));

            // Q3: Message ID lookup (multikey index for array field)
            transactions.createIndex(
                    Indexes.ascending("source_message_ids"),
                    new IndexOptions().name("idx_message_ids"));

            // Uniqueness constraint - prevents duplicates during backfill
            transactions.createIndex(
                    Indexes.compoundIndex(
                            Indexes.ascending("account_last4"),
                            Indexes.ascending("occurred_at"),
                            Indexes.ascending("direction"),
                            Indexes.ascending("amount")),
                    new IndexOptions()
                            .name("idx_unique_txn")
                            .unique(true));

        } catch (MongoException e) {
            // Index creation failures are non-fatal if indexes already exist
            System.err.println("Warning: Could not create indexes: " + e.getMessage());
        }
    }

    @Override
    public void save(NormalizedTxn txn) {
        Document doc = toDocument(txn);
        try {
            // Use replaceOne with upsert for idempotency
            Bson filter = Filters.and(
                    Filters.eq("account_last4", txn.accountLast4()),
                    Filters.eq("occurred_at", txn.occurredAt().toString()),
                    Filters.eq("direction", txn.direction().name()),
                    Filters.eq("amount", txn.amount().toPlainString()));
            
            transactions.replaceOne(filter, doc, 
                    new com.mongodb.client.model.ReplaceOptions().upsert(true));
        } catch (MongoException e) {
            throw new IllegalStateException("Could not save transaction: " + txn, e);
        }
    }

    /**
     * Q1: One account's transactions for one month, newest first.
     * 
     * Query uses idx_account_month_time index.
     * At 100k transactions: examines only matching month's transactions.
     */
    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        String accountMonthKey = accountLast4 + "-" + month.format(
                DateTimeFormatter.ofPattern("yyyy-MM"));
        
        List<NormalizedTxn> results = new ArrayList<>();
        try {
            transactions.find(Filters.eq("accountMonth", accountMonthKey))
                    .sort(Sorts.descending("occurredAtMillis"))
                    .forEach(doc -> results.add(fromDocument(doc)));
        } catch (MongoException e) {
            throw new IllegalStateException(
                    "Could not query account " + accountLast4 + " for " + month, e);
        }
        return results;
    }

    /**
     * Q2: Running totals per category for an account.
     * 
     * Uses idx_account index + aggregation pipeline.
     * At 100k transactions: examines only this account's transactions.
     */
    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        Map<Category, BigDecimal> totals = new EnumMap<>(Category.class);
        for (Category c : Category.values()) {
            totals.put(c, BigDecimal.ZERO.setScale(2));
        }

        try {
            // Simple aggregation: filter by account, group by category, sum amounts
            List<Document> pipeline = Arrays.asList(
                    new Document("$match", new Document("account_last4", accountLast4)),
                    new Document("$group", new Document("_id", "$category")
                            .append("total", new Document("$sum", new Document("$toDouble", "$amount"))))
            );

            database.getCollection("transactions")
                    .aggregate(pipeline)
                    .forEach(doc -> {
                        String categoryName = doc.getString("_id");
                        double total = doc.getDouble("total");
                        Category category = Category.valueOf(categoryName);
                        totals.put(category, BigDecimal.valueOf(total).setScale(2));
                    });
        } catch (MongoException e) {
            throw new IllegalStateException(
                    "Could not compute category totals for account " + accountLast4, e);
        }
        return totals;
    }

    /**
     * Q3: Which transaction, if any, did this message produce?
     * 
     * Uses idx_message_ids multikey index.
     * At 100k transactions: examines 1-2 documents (message IDs are unique or shared by SMS+email).
     */
    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        try {
            Document doc = transactions.find(Filters.eq("source_message_ids", messageId))
                    .first();
            return doc == null ? Optional.empty() : Optional.of(fromDocument(doc));
        } catch (MongoException e) {
            throw new IllegalStateException(
                    "Could not look up message " + messageId, e);
        }
    }

    /**
     * For testing/benchmarking: count documents in collection.
     */
    public long count() {
        return transactions.countDocuments();
    }

    /**
     * For testing/benchmarking: clear all transactions.
     */
    public void clear() {
        transactions.deleteMany(new Document());
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
    }

    // ── Document Conversion ──────────────────────────────────────────────────

    private Document toDocument(NormalizedTxn txn) {
        OffsetDateTime occurredAt = txn.occurredAt();
        YearMonth yearMonth = YearMonth.from(occurredAt);
        String accountMonth = txn.accountLast4() + "-" 
                + yearMonth.format(DateTimeFormatter.ofPattern("yyyy-MM"));

        return new Document()
                .append("account_last4", txn.accountLast4())
                .append("occurred_at", occurredAt.toString())
                .append("occurredAtMillis", occurredAt.toInstant().toEpochMilli())
                .append("accountMonth", accountMonth)
                .append("direction", txn.direction().name())
                .append("amount", txn.amount().toPlainString())
                .append("category", txn.category().name())
                .append("merchant", txn.merchant())
                .append("source_message_ids", txn.sourceMessageIds());
    }

    private NormalizedTxn fromDocument(Document doc) {
        @SuppressWarnings("unchecked")
        List<String> messageIds = (List<String>) doc.get("source_message_ids");
        
        return new NormalizedTxn(
                doc.getString("account_last4"),
                OffsetDateTime.parse(doc.getString("occurred_at")),
                Direction.valueOf(doc.getString("direction")),
                new BigDecimal(doc.getString("amount")),
                Category.valueOf(doc.getString("category")),
                doc.getString("merchant"),
                messageIds);
    }
}
