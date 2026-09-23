# Ledger Sync — Simplify Money Backend Assignment

Backend Java take-home assignment for Simplify Money.

The service reads bank SMS/email messages, normalizes them into transactions, generates ledger reports, and mirrors the SQL ledger into MongoDB for document-based access.

## Tech Stack

- Java 21
- Gradle
- MongoDB 7.0
- Docker Compose
- JUnit 5
- MongoDB Java Driver 4.11.1
- GitHub Actions

---

## 1. Quick Start

### Prerequisites

- Java 21
- Docker Desktop
- Git

Clone the repository and enter the project directory.

### Start MongoDB

```bash
docker compose up -d
```

Check the MongoDB container:

```bash
docker compose ps
```

The MongoDB container should be running and healthy.

### Run the test suite

```bash
./gradlew test
```

### Run the dependency-aware self-check

```bash
./verify.sh
```

### Run the application pipeline

```bash
./gradlew run --args="migrate"
./gradlew run --args="ingest fixtures/corpus-a.jsonl"
./gradlew run --args="report submission/"
```

The generated reports are:

```text
submission/ledger.json
submission/summary.json
submission/reconciliation.json
```

---

## 2. What I Built

The original repository contained a partially implemented ledger pipeline.

I completed the missing transaction/document-store functionality and implemented the MongoDB-backed document store.

The main areas covered are:

- Transaction normalization
- Deduplication
- Transaction categorization
- Reporting
- Reconciliation
- MongoDB document storage
- MongoDB indexes
- SQL → MongoDB backfill
- SQL/MongoDB consistency checking
- Document-store benchmarking
- Runtime verification

The existing frozen model contracts were kept unchanged.

---

## 3. Why MongoDB

The assignment allowed either DynamoDB or MongoDB, with DynamoDB preferred.

I chose MongoDB because it provides a reproducible local setup through Docker Compose and maps naturally to the three required document-store access patterns.

The main reasons were:

- It runs locally through Docker Compose.
- Documents are easy to inspect during development.
- The MongoDB Java driver integrates directly with the existing Gradle project.
- The three required access patterns map naturally to MongoDB indexes.
- It avoids introducing AWS infrastructure for a local take-home assignment.
- MongoDB provides query execution statistics such as `totalDocsExamined` and `nReturned`, which can be used directly for the required benchmark.

The trade-off is that DynamoDB may be a more natural choice for an AWS-native production environment, but MongoDB made the implementation and verification reproducible locally.

---

## 4. Document Model

Each normalized transaction is stored as one MongoDB document.

A simplified document looks like:

```json
{
  "account_last4": "4821",
  "occurred_at": "2026-07-04T20:24:00+05:30",
  "occurredAtMillis": 1783176840000,
  "accountMonth": "4821:2026-07",
  "direction": "debit",
  "amount": "2499.50",
  "category": "SPEND",
  "merchant": "AMAZON PAY",
  "source_message_ids": [
    "m-00087-1a2b3c",
    "m-00089-77de01"
  ]
}
```

The document contains the fields required by the three document-store queries directly instead of requiring an expensive reconstruction of the transaction.

### Main fields

| Field | Purpose |
|---|---|
| `account_last4` | Identifies the account |
| `occurred_at` | Transaction time |
| `occurredAtMillis` | Efficient time sorting |
| `accountMonth` | Direct account/month lookup key |
| `direction` | Debit or credit |
| `amount` | Exact transaction amount |
| `category` | `SPEND`, `INCOME`, `MICRO`, or `TRANSFER` |
| `merchant` | Merchant/payee information |
| `source_message_ids` | Traceability back to source messages |

---

## 5. Indexes

The document store uses indexes based on the actual access patterns.

### Account + month query

```text
{ accountMonth: 1, occurredAtMillis: -1 }
```

This allows the service to find one account/month directly and return transactions newest first.

### Account query

```text
{ account_last4: 1 }
```

This supports filtering an account before performing category aggregation.

### Message ID lookup

```text
{ source_message_ids: 1 }
```

This is a multikey index because one transaction can have multiple source message IDs.

### Additional transaction lookup index

The implementation also maintains a compound index involving:

```text
account_last4
occurred_at
direction
amount
```

This supports identifying transactions during idempotent writes/backfill.

---

## 6. Three Required Query Patterns

The document store implements the three required access patterns.

### Query 1 — Account/month

Find one account's transactions for one month, newest first.

```text
forAccountMonth(account, month)
```

### Query 2 — Category totals

Calculate running totals per category for an account.

```text
categoryTotals(account)
```

### Query 3 — Message ID

Find the transaction produced by a particular source message.

```text
byMessageId(messageId)
```

---

## 7. MongoDB Benchmark Results

The benchmark was executed against:

```text
100,000 transactions
```

The following are the recorded MongoDB execution statistics.

### Query 1

```text
forAccountMonth("4821", "2020-07")

totalDocsExamined: 382
nReturned: 382
```

The query examined exactly the documents it returned.

The composite:

```text
accountMonth + occurredAtMillis
```

index allows MongoDB to seek directly to the account/month range and return the records in descending time order.

### Query 2

```text
categoryTotals("4821")

totalDocsExamined: 24,750
nReturned: 4
```

The query examines the relevant account documents and then groups them into the four categories.

### Query 3

```text
byMessageId("m-bench-12345")

totalDocsExamined: 1
nReturned: 1
```

The multikey index on:

```text
source_message_ids
```

allows direct lookup of the transaction associated with the message ID.

---

## 8. Required Six Numbers

| Query | `totalDocsExamined` | `nReturned` |
|---|---:|---:|
| `forAccountMonth("4821", "2020-07")` | 382 | 382 |
| `categoryTotals("4821")` | 24,750 | 4 |
| `byMessageId("m-bench-12345")` | 1 | 1 |

These numbers were obtained from the MongoDB benchmark rather than being theoretical estimates.

---

## 9. Backfill

The SQL → MongoDB backfill was executed successfully.

Result:

```text
Read from SQL:        236
Written to documents: 236
Skipped (duplicates):   0
```

The backfill is designed to be safe to run repeatedly.

The implementation uses an idempotent write strategy so that repeated execution does not create duplicate transactions.

This is important because the assignment states that the backfill may be run multiple times, including after a partial failure.

---

## 10. Consistency Check

The consistency checker was executed after the backfill.

Result:

```text
Stores are consistent. No divergences found.
```

The checker compares transaction data rather than relying only on row/document counts.

This is important because two stores can contain the same number of records while still containing different transaction data.

---

## 11. Test Results

The complete test suite was executed successfully.

### Test summary

```text
AmountsTest:                 5 tests, 0 failures
DocumentStoreTest:           7 tests, 0 failures
IncidentINC20260911Test:     4 tests, 0 failures
NormalizedTxnContractTest:   6 tests, 0 failures
```

Total:

```text
22 tests
22 passed
0 failures
0 errors
```

The existing tests remained green and the document-store tests also passed.

---

## 12. Decision Log

### Decision 1 — MongoDB instead of DynamoDB

I considered DynamoDB because the assignment preferred it.

I chose MongoDB because the assignment needed a reproducible local Docker setup and MongoDB made it easier to inspect documents and query execution statistics locally.

With an AWS deployment target, I would evaluate DynamoDB again based on the operational requirements.

### Decision 2 — One transaction per document

I kept one normalized transaction as one MongoDB document.

I considered embedding multiple transactions into larger account documents, but that would make updates, deduplication, and message-level traceability harder.

One transaction per document also makes the three required queries easier to reason about.

### Decision 3 — Store source message IDs as an array

A transaction can be evidenced by more than one SMS/email.

Instead of storing only one message ID, I kept:

```text
source_message_ids
```

as an array.

This preserves traceability when multiple messages describe the same transaction.

### Decision 4 — Add an account/month access key

The first required query is specifically account + month.

Rather than making MongoDB derive the month for every query, I stored:

```text
accountMonth
```

and indexed it.

This makes the required access pattern explicit in the document model.

### Decision 5 — Keep amount as an exact decimal representation

Money should not be handled through binary floating-point arithmetic.

The transaction amount is stored in its exact decimal representation, for example:

```text
2499.50
```

rather than relying on binary floating-point values.

This follows the assignment's requirement that monetary values preserve paisa-level precision.

### Decision 6 — Use a multikey index for message IDs

A transaction can have multiple source messages.

MongoDB's multikey index on:

```text
source_message_ids
```

allows a message ID to directly locate its transaction.

The benchmark confirmed this with:

```text
1 document examined
1 document returned
```

### Decision 7 — Make backfill idempotent

The assignment explicitly says that backfill may run multiple times and may be resumed after partial failure.

I therefore did not treat backfill as a simple blind insert.

The document store uses an idempotent write strategy so repeated execution does not create duplicate transactions.

### Decision 8 — Verify query performance using actual MongoDB statistics

I did not want to rely only on index definitions to describe performance.

I ran the implementation against 100,000 transactions and collected:

```text
totalDocsExamined
nReturned
```

This provided concrete benchmark results for the README.

### Decision 9 — Keep the CI self-check compatible with the dependency graph

The original `verify.sh` used direct `javac` compilation.

After the MongoDB implementation was added, the source code required the MongoDB driver on the classpath.

I changed the self-check to use the Gradle wrapper:

```bash
./gradlew --no-daemon --console=plain selfCheck
```

This uses the project's Gradle dependency configuration during compilation.

I also fixed the executable permission for the Gradle wrapper so it can run on GitHub's Linux runner.

Finally, I fixed an issue where an empty `--args` value caused Gradle to fail when `verify.sh` was run without arguments. The script now passes `--args` only when arguments are actually provided.

---

## 13. What the Data Made Me Decide

The corpus and benchmark affected several implementation choices.

### Transaction vs message

The input contains messages, but the output represents real transactions.

Therefore, multiple messages describing the same transaction are treated as evidence for one transaction rather than automatically creating multiple ledger rows.

### Source traceability matters

Because a transaction can be supported by multiple messages, the final document preserves all relevant source message IDs.

### Account/month is a real access pattern

The benchmark confirmed that the account/month composite index allows MongoDB to examine exactly the returned documents for the tested query.

### Aggregation is different

The category-total query cannot simply return four documents without examining the relevant account transactions because the totals have to be calculated.

The benchmark showed:

```text
24,750 examined
4 returned
```

### Message lookup is highly selective

The message ID query examined only one document for the benchmark case.

This supported keeping a dedicated index on `source_message_ids`.

### Further performance testing

With more time, I would run the benchmark across multiple data distributions rather than only one generated 100,000-document dataset.

In particular, I would test:

- Accounts with very different transaction counts
- Months with uneven transaction distributions
- Duplicate-heavy inputs
- Larger source-message arrays
- Larger overall datasets

---

## 14. Incident

The open incident concerned a ₹5 water-can transaction where the available balance of ₹92,213.10 was incorrectly treated as the transaction amount.

The issue was reproduced, traced to parsing behavior, fixed, and covered by a regression test.

The important lesson from the incident was that an existing green test suite did not necessarily prove that every real-world message format was covered.

The regression test was added specifically to cover the affected parsing behavior.

---

## 15. AI Disclosure

I used AI assistance during the assignment.

### Areas where AI was used

I used an AI coding assistant for:

- Understanding the existing codebase
- Discussing implementation approaches
- Generating and adjusting code during development
- Debugging compilation and CI failures
- Checking Docker/Gradle commands
- Reviewing test output
- Helping structure documentation

I still ran the code, inspected the actual output, investigated failures, and made the final implementation decisions.

### A concrete case where the AI output was incorrect

One concrete example was the first CI self-check implementation.

The initial approach changed `verify.sh` to compile the Java sources directly using `javac`:

```bash
javac -d build/selfcheck $(find src/main/java -name '*.java')
```

This looked reasonable because the original script described itself as dependency-free.

However, after the MongoDB implementation was added, the source code imported MongoDB classes such as:

```text
com.mongodb.client.MongoClient
com.mongodb.client.MongoClients
org.bson.Document
```

The GitHub Actions runner then failed because `javac` did not have the MongoDB driver on its classpath.

The CI output contained errors such as:

```text
package com.mongodb.client does not exist
package org.bson does not exist
```

I changed the approach to use the Gradle wrapper:

```bash
./gradlew --no-daemon --console=plain selfCheck
```

This uses the project's actual dependency configuration and therefore includes the MongoDB driver.

There was also a second CI issue caused by passing an empty Gradle `--args` value when `verify.sh` was called without arguments.

I changed the script so `--args` is only supplied when arguments actually exist.

This was a useful example of why AI-generated changes were not treated as automatically correct. The failure was reproduced in CI, the dependency/classpath problem was identified, and the implementation was corrected based on the actual error.

---

## 16. What's Unfinished or Not Independently Verified

I am intentionally listing areas that were not independently verified rather than claiming everything was complete.

### Task 1 — Transaction evidence / Track Teardown

During the product/track teardown, I could not independently verify two genuine wrong or missed transactions from the available UI evidence.

I did not invent transaction examples just to satisfy the requirement.

The Track Teardown document records this limitation and explains why the displayed sample amount was not treated as evidence of an automatically detected transaction error.

### Further performance testing

The required 100,000-transaction benchmark was completed.

With more time, I would test additional data distributions and larger datasets.

### Production deployment

The assignment did not require a deployed URL.

The implementation was therefore validated locally through Docker Compose and GitHub Actions rather than deployed to a cloud environment.

---

## 17. Verification Summary

The final implementation was verified with:

| Verification | Result |
|---|---|
| MongoDB via Docker Compose | PASS |
| Java compilation | PASS |
| Automated tests | 22 passed |
| Backfill | PASS |
| SQL transactions migrated | 236 |
| Duplicate writes during run | 0 |
| Consistency check | PASS |
| 100,000 transaction benchmark | PASS |
| Query 1 | 382 / 382 |
| Query 2 | 24,750 / 4 |
| Query 3 | 1 / 1 |
| GitHub Actions self-check | PASS |

### Benchmark summary

```text
Query 1: 382 examined / 382 returned
Query 2: 24,750 examined / 4 returned
Query 3: 1 examined / 1 returned
```

---

## 18. Repository

The repository contains the implementation, tests, Docker Compose configuration, verification script, Gradle wrapper, and commit history.

Repository:

[GitHub Repository](https://github.com/dev-Pankaj7/-ledger-sync-task)
