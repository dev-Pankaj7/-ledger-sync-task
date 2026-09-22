package in.simplifymoney.ledgersync;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.MongoDocumentStore;
import org.bson.Document;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Benchmark tool to measure DocumentStore query performance at scale.
 *
 * Generates 100,000 synthetic transactions and measures:
 * - Query 1: examined vs returned for account-month query
 * - Query 2: examined vs returned for category totals
 * - Query 3: examined vs returned for message ID lookup
 *
 * Usage: gradle run --args="benchmark"
 *
 * Prerequisites: docker compose up -d
 */
public final class DocumentStoreBenchmark {

    private static final int TARGET_TRANSACTIONS = 100_000;
    private static final String[] ACCOUNTS = {"4821", "9075", "1234", "5678"};
    private static final String[] MERCHANTS = {
            "AMAZON", "SWIGGY", "BIGBASKET", "UBER", "OLA", "NETFLIX",
            "UPI/TEA", "UPI/MILK", "UPI/WATER", "SALARY", "RENT"
    };

    public static void main(String[] args) {
        System.out.println("=== DocumentStore Performance Benchmark ===\n");

        try (MongoDocumentStore store = new MongoDocumentStore()) {
            // Clean slate
            System.out.println("Clearing existing data...");
            store.clear();

            // Generate test data
            System.out.println("Generating " + TARGET_TRANSACTIONS + " transactions...");
            generateTestData(store, TARGET_TRANSACTIONS);
            System.out.println("Data generation complete. Collection size: " + store.count());

            // Run benchmarks
            System.out.println("\n--- Query Performance Metrics ---\n");

            benchmarkQuery1(store);
            benchmarkQuery2(store);
            benchmarkQuery3(store);

            System.out.println("\n=== Benchmark Complete ===");

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("\nMake sure MongoDB is running:");
            System.err.println("  docker compose up -d");
            e.printStackTrace();
        }
    }

    private static void generateTestData(MongoDocumentStore store, int count) {
        Random rand = new Random(42); // Fixed seed for reproducibility
        OffsetDateTime baseTime = OffsetDateTime.parse("2020-01-01T00:00:00+05:30");

        for (int i = 0; i < count; i++) {
            String account = ACCOUNTS[rand.nextInt(ACCOUNTS.length)];
            OffsetDateTime occurredAt = baseTime.plusDays(rand.nextInt(2000))
                    .plusHours(rand.nextInt(24));

            Direction direction = rand.nextBoolean() ? Direction.DEBIT : Direction.CREDIT;
            BigDecimal amount = new BigDecimal(
                    String.format("%.2f", rand.nextDouble() * 10000));

            Category category;
            if (direction == Direction.CREDIT) {
                category = Category.INCOME;
            } else {
                if (amount.compareTo(new BigDecimal("100.00")) <= 0
                        && rand.nextBoolean()) {
                    category = Category.MICRO;
                } else {
                    category = Category.SPEND;
                }
            }

            String merchant = MERCHANTS[rand.nextInt(MERCHANTS.length)];
            List<String> messageIds;
            if (rand.nextBoolean()) {
                messageIds = List.of("m-bench-" + i, "m-bench-" + i + "-email");
            } else {
                messageIds = List.of("m-bench-" + i);
            }

            NormalizedTxn txn = new NormalizedTxn(
                    account, occurredAt, direction, amount,
                    category, merchant, messageIds);

            store.save(txn);

            if ((i + 1) % 10000 == 0) {
                System.out.println("  Generated " + (i + 1) + " transactions...");
            }
        }
    }

    /**
     * Q1: One account's transactions for one month, newest first.
     */
    private static void benchmarkQuery1(MongoDocumentStore store) {
        System.out.println("Query 1: forAccountMonth(\"4821\", 2020-07)");

        // Execute query
        YearMonth month = YearMonth.of(2020, 7);
        List<NormalizedTxn> results = store.forAccountMonth("4821", month);

        // Get MongoDB explain stats
        QueryStats stats = getMongoStats(
                "{ accountMonth: \"4821-2020-07\" }",
                "{ occurredAtMillis: -1 }");

        System.out.println("  Items examined: " + stats.examined);
        System.out.println("  Items returned: " + results.size());
        System.out.println("  Efficiency: " + calculateEfficiency(stats.examined, results.size()));
        System.out.println();
    }

    /**
     * Q2: Running totals per category for an account.
     */
    private static void benchmarkQuery2(MongoDocumentStore store) {
        System.out.println("Query 2: categoryTotals(\"4821\")");

        Map<Category, BigDecimal> totals = store.categoryTotals("4821");

        // For aggregation, estimate examined docs
        long accountDocs = countAccountDocs("4821");

        System.out.println("  Items examined (est): " + accountDocs);
        System.out.println("  Items returned: " + totals.size());
        System.out.println("  Efficiency: aggregated " + accountDocs 
                + " docs into " + totals.size() + " categories");
        System.out.println();
    }

    /**
     * Q3: Which transaction did this message produce?
     */
    private static void benchmarkQuery3(MongoDocumentStore store) {
        System.out.println("Query 3: byMessageId(\"m-bench-12345\")");

        Optional<NormalizedTxn> result = store.byMessageId("m-bench-12345");

        QueryStats stats = getMongoStats(
                "{ source_message_ids: \"m-bench-12345\" }",
                "{}");

        System.out.println("  Items examined: " + stats.examined);
        System.out.println("  Items returned: " + (result.isPresent() ? 1 : 0));
        System.out.println("  Efficiency: " + calculateEfficiency(stats.examined,
                result.isPresent() ? 1 : 0));
        System.out.println();
    }

    private static QueryStats getMongoStats(String filter, String sort) {
        try (MongoClient client = MongoClients.create(
                "mongodb://ledger:ledger@localhost:27017")) {
            MongoDatabase db = client.getDatabase("ledger");

            // Build explain command
            Document explain = new Document("find", "transactions")
                    .append("filter", Document.parse(filter));

            if (!sort.equals("{}")) {
                explain.append("sort", Document.parse(sort));
            }

            Document explainCmd = new Document("explain", explain)
                    .append("verbosity", "executionStats");

            Document result = db.runCommand(explainCmd);

            // Extract stats from explain output
            Document execStats = result.get("executionStats", Document.class);
            long examined = execStats.getInteger("totalDocsExamined", 0);
            long returned = execStats.getInteger("nReturned", 0);

            return new QueryStats(examined, returned);
        } catch (Exception e) {
            System.err.println("  Warning: Could not get MongoDB explain stats: "
                    + e.getMessage());
            return new QueryStats(0, 0);
        }
    }

    private static long countAccountDocs(String account) {
        try (MongoClient client = MongoClients.create(
                "mongodb://ledger:ledger@localhost:27017")) {
            MongoDatabase db = client.getDatabase("ledger");
            return db.getCollection("transactions")
                    .countDocuments(Document.parse("{ account_last4: \"" + account + "\" }"));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String calculateEfficiency(long examined, long returned) {
        if (examined == 0) return "N/A";
        double ratio = (double) returned / examined * 100;
        return String.format("%.1f%% (%.1fx)", ratio, (double) examined / Math.max(1, returned));
    }

    private record QueryStats(long examined, long returned) {}
}
