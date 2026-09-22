package in.simplifymoney.ledgersync;

import java.nio.file.Files;
import java.nio.file.Path;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;

/**
 * Command line entry point.
 *
 *   migrate                  apply db/migration/*.sql
 *   ingest  <corpus.jsonl>   read a corpus into the ledger
 *   report  <out-dir>        write ledger.json, summary.json, reconciliation.json
 *   backfill                 move SQL ledger data to document store
 *   check                    verify SQL and document store consistency
 *   benchmark                run document store performance benchmark
 */
public final class App {

    private static final Path DB = Path.of("data", "ledger");
    private static final Path MIGRATIONS = Path.of("db", "migration");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: migrate | ingest <corpus.jsonl> | report <out-dir> | backfill | check | benchmark");
            System.exit(2);
        }
        Files.createDirectories(DB.getParent());

        switch (args[0]) {
            case "migrate" -> {
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    store.migrate(MIGRATIONS);
                    System.out.println("ledger rows: " + store.count());
                }
            }
            case "ingest" -> {
                if (args.length < 2) throw new IllegalArgumentException("ingest needs a corpus");
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    store.migrate(MIGRATIONS);
                    // Clear existing rows so re-running produces identical output.
                    // The seed in V2__seed.sql represents corrupted pre-fix production data;
                    // a fresh ingest replaces it with correctly parsed transactions.
                    store.clear();
                    var stats = new IngestService(new Parsers(), store,
                            p -> {
                                if (p.statedBalance() != null) {
                                    store.saveCheckpoint(p.accountLast4(),
                                            p.occurredAt(),
                                            p.statedBalance(),
                                            p.sourceMessageId());
                                }
                            })
                            .ingestFile(Path.of(args[1]));
                    System.out.println(stats);
                    System.out.println("ledger rows: " + store.count());
                }
            }
            case "report" -> {
                if (args.length < 2) throw new IllegalArgumentException("report needs a directory");
                Path out = Path.of(args[1]);
                Files.createDirectories(out);
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    var ledger = store.all();
                    var checkpoints = store.allCheckpoints();
                    Files.writeString(out.resolve("ledger.json"),
                            Json.writePretty(Reports.ledgerDocument(ledger)));
                    Files.writeString(out.resolve("summary.json"),
                            Json.writePretty(Reports.summary(ledger)));
                    Files.writeString(out.resolve("reconciliation.json"),
                            Json.writePretty(Reports.reconciliation(ledger, checkpoints)));
                    System.out.println("wrote 3 files to " + out);
                }
            }
            case "backfill" -> {
                System.out.println("Backfilling SQL ledger to document store...");
                try (SqlLedgerStore sql = new SqlLedgerStore(DB);
                     var docStore = new in.simplifymoney.ledgersync.store.MongoDocumentStore()) {
                    var backfill = new in.simplifymoney.ledgersync.store.Backfill(sql, docStore);
                    var result = backfill.run();
                    System.out.println("Backfill complete:");
                    System.out.println("  Read from SQL: " + result.read());
                    System.out.println("  Written to documents: " + result.written());
                    System.out.println("  Skipped (duplicates): " + result.skipped());
                }
            }
            case "check" -> {
                System.out.println("Checking consistency between SQL and document store...");
                try (SqlLedgerStore sql = new SqlLedgerStore(DB);
                     var docStore = new in.simplifymoney.ledgersync.store.MongoDocumentStore()) {
                    var checker = new in.simplifymoney.ledgersync.store.ConsistencyChecker(
                            sql, docStore);
                    var divergences = checker.check();
                    if (divergences.isEmpty()) {
                        System.out.println("✓ Stores are consistent. No divergences found.");
                    } else {
                        System.out.println("✗ Found " + divergences.size() + " divergence(s):");
                        for (var d : divergences) {
                            System.out.println("\n  " + d.what());
                            System.out.println("    SQL:       " + d.inSql());
                            System.out.println("    Documents: " + d.inDocuments());
                        }
                        System.exit(1);
                    }
                }
            }
            case "benchmark" -> {
                DocumentStoreBenchmark.main(args);
            }
            default -> {
                System.err.println("unknown command: " + args[0]);
                System.exit(2);
            }
        }
    }
}
