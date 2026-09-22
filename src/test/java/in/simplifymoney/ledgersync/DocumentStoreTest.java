package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.Backfill;
import in.simplifymoney.ledgersync.store.ConsistencyChecker;
import in.simplifymoney.ledgersync.store.MongoDocumentStore;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MongoDB DocumentStore implementation.
 *
 * NOTE: These tests require MongoDB to be running.
 * Run: docker compose up -d
 *
 * If MongoDB is not available, these tests will be skipped.
 */
class DocumentStoreTest {

    private MongoDocumentStore store;
    private boolean mongoAvailable = true;

    @BeforeEach
    void setup() {
        try {
            store = new MongoDocumentStore();
            store.clear();
        } catch (Exception e) {
            System.err.println("MongoDB not available. Skipping DocumentStore tests.");
            System.err.println("To run these tests: docker compose up -d");
            mongoAvailable = false;
        }
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            try {
                store.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @DisplayName("save and query by message ID")
    void saveAndQueryByMessageId() {
        if (!mongoAvailable) return;

        NormalizedTxn txn = new NormalizedTxn(
                "4821",
                OffsetDateTime.parse("2026-07-01T09:02+05:30"),
                Direction.CREDIT,
                new BigDecimal("45000.00"),
                Category.INCOME,
                "SALARY",
                List.of("m-001", "m-002"));

        store.save(txn);

        Optional<NormalizedTxn> found = store.byMessageId("m-001");
        assertTrue(found.isPresent());
        assertEquals("4821", found.get().accountLast4());
        assertEquals(new BigDecimal("45000.00"), found.get().amount());
    }

    @Test
    @DisplayName("query transactions by account and month")
    void queryByAccountMonth() {
        if (!mongoAvailable) return;

        // Save transactions in July 2026
        store.save(new NormalizedTxn("4821",
                OffsetDateTime.parse("2026-07-01T09:00+05:30"),
                Direction.CREDIT, new BigDecimal("100.00"),
                Category.INCOME, "SALARY", List.of("m-1")));

        store.save(new NormalizedTxn("4821",
                OffsetDateTime.parse("2026-07-15T14:00+05:30"),
                Direction.DEBIT, new BigDecimal("50.00"),
                Category.SPEND, "SHOP", List.of("m-2")));

        // Save transaction in August 2026
        store.save(new NormalizedTxn("4821",
                OffsetDateTime.parse("2026-08-01T10:00+05:30"),
                Direction.DEBIT, new BigDecimal("25.00"),
                Category.SPEND, "CAFE", List.of("m-3")));

        List<NormalizedTxn> julyTxns = store.forAccountMonth("4821",
                YearMonth.of(2026, 7));

        assertEquals(2, julyTxns.size());
        // Newest first
        assertEquals(new BigDecimal("50.00"), julyTxns.get(0).amount());
        assertEquals(new BigDecimal("100.00"), julyTxns.get(1).amount());

        List<NormalizedTxn> augTxns = store.forAccountMonth("4821",
                YearMonth.of(2026, 8));
        assertEquals(1, augTxns.size());
    }

    @Test
    @DisplayName("compute category totals for an account")
    void categoryTotals() {
        if (!mongoAvailable) return;

        store.save(new NormalizedTxn("4821",
                OffsetDateTime.parse("2026-07-01T09:00+05:30"),
                Direction.CREDIT, new BigDecimal("1000.00"),
                Category.INCOME, "SALARY", List.of("m-1")));

        store.save(new NormalizedTxn("4821",
                OffsetDateTime.parse("2026-07-02T10:00+05:30"),
                Direction.DEBIT, new BigDecimal("100.00"),
                Category.SPEND, "SHOP", List.of("m-2")));

        store.save(new NormalizedTxn("4821",
                OffsetDateTime.parse("2026-07-03T11:00+05:30"),
                Direction.DEBIT, new BigDecimal("50.00"),
                Category.SPEND, "CAFE", List.of("m-3")));

        store.save(new NormalizedTxn("4821",
                OffsetDateTime.parse("2026-07-04T12:00+05:30"),
                Direction.DEBIT, new BigDecimal("5.00"),
                Category.MICRO, "UPI/TEA", List.of("m-4")));

        Map<Category, BigDecimal> totals = store.categoryTotals("4821");

        assertEquals(new BigDecimal("1000.00"), totals.get(Category.INCOME));
        assertEquals(new BigDecimal("150.00"), totals.get(Category.SPEND));
        assertEquals(new BigDecimal("5.00"), totals.get(Category.MICRO));
        assertEquals(new BigDecimal("0.00"), totals.get(Category.TRANSFER));
    }

    @Test
    @DisplayName("save is idempotent - duplicate saves do not create duplicates")
    void saveIdempotency() {
        if (!mongoAvailable) return;

        NormalizedTxn txn = new NormalizedTxn("4821",
                OffsetDateTime.parse("2026-07-01T09:00+05:30"),
                Direction.CREDIT, new BigDecimal("100.00"),
                Category.INCOME, "SALARY", List.of("m-1"));

        store.save(txn);
        store.save(txn);
        store.save(txn);

        assertEquals(1, store.count());
    }

    @Test
    @DisplayName("backfill moves SQL data to document store and deduplicates")
    void backfillTest() {
        if (!mongoAvailable) return;

        Path dbFile = Path.of("data", "test-backfill");
        try (SqlLedgerStore sql = new SqlLedgerStore(dbFile)) {
            sql.migrate(Path.of("db", "migration"));
            sql.clear();

            // Add duplicate transactions to SQL (simulating historical data without uniqueness)
            NormalizedTxn txn1 = new NormalizedTxn("4821",
                    OffsetDateTime.parse("2026-07-01T09:00+05:30"),
                    Direction.CREDIT, new BigDecimal("100.00"),
                    Category.INCOME, "SALARY", List.of("m-1"));

            sql.save(txn1);
            sql.save(txn1); // Duplicate
            sql.save(txn1); // Duplicate

            assertEquals(3, sql.count(), "SQL should have 3 rows (with duplicates)");

            // Run backfill
            Backfill backfill = new Backfill(sql, store);
            Backfill.Result result = backfill.run();

            assertEquals(3, result.read(), "Should read 3 rows from SQL");
            assertEquals(1, result.written(), "Should write 1 unique transaction");
            assertEquals(2, result.skipped(), "Should skip 2 duplicates");
            assertEquals(1, store.count(), "Document store should have 1 transaction");
        }
    }

    @Test
    @DisplayName("consistency checker detects missing transaction")
    void consistencyCheckerMissing() {
        if (!mongoAvailable) return;

        Path dbFile = Path.of("data", "test-consistency");
        try (SqlLedgerStore sql = new SqlLedgerStore(dbFile)) {
            sql.migrate(Path.of("db", "migration"));
            sql.clear();

            // Add transaction to SQL only
            NormalizedTxn txn = new NormalizedTxn("4821",
                    OffsetDateTime.parse("2026-07-01T09:00+05:30"),
                    Direction.CREDIT, new BigDecimal("100.00"),
                    Category.INCOME, "SALARY", List.of("m-1"));
            sql.save(txn);

            // Do NOT add to document store
            ConsistencyChecker checker = new ConsistencyChecker(sql, store);
            List<ConsistencyChecker.Divergence> divergences = checker.check();

            assertEquals(1, divergences.size());
            assertTrue(divergences.get(0).what().contains("Missing"));
        }
    }

    @Test
    @DisplayName("consistency checker detects modified amount")
    void consistencyCheckerModifiedAmount() {
        if (!mongoAvailable) return;

        Path dbFile = Path.of("data", "test-modified");
        try (SqlLedgerStore sql = new SqlLedgerStore(dbFile)) {
            sql.migrate(Path.of("db", "migration"));
            sql.clear();

            // Add correct transaction to SQL
            NormalizedTxn sqlTxn = new NormalizedTxn("4821",
                    OffsetDateTime.parse("2026-07-01T09:00+05:30"),
                    Direction.CREDIT, new BigDecimal("100.00"),
                    Category.INCOME, "SALARY", List.of("m-1"));
            sql.save(sqlTxn);

            // Add modified transaction to document store (different amount)
            NormalizedTxn docTxn = new NormalizedTxn("4821",
                    OffsetDateTime.parse("2026-07-01T09:00+05:30"),
                    Direction.CREDIT, new BigDecimal("999.99"), // MODIFIED
                    Category.INCOME, "SALARY", List.of("m-1"));
            store.save(docTxn);

            ConsistencyChecker checker = new ConsistencyChecker(sql, store);
            List<ConsistencyChecker.Divergence> divergences = checker.check();

            assertEquals(1, divergences.size());
            assertTrue(divergences.get(0).what().contains("Amount mismatch"));
            assertTrue(divergences.get(0).inSql().contains("100.00"));
            assertTrue(divergences.get(0).inDocuments().contains("999.99"));
        }
    }
}
