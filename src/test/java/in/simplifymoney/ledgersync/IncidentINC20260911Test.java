package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.Amounts;
import in.simplifymoney.ledgersync.parse.HdfcSmsParser;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression test for INC-2026-09-11.
 *
 * Root cause: Amounts.first() required a decimal point (\.[0-9]{2}) so it
 * failed to match integer amounts like "Rs.5". It fell through and matched
 * the first decimal figure it found — which was "Rs.92,213.10" in the
 * "Avl Bal" clause. The transaction was recorded as ₹92,213.10 instead of ₹5.
 *
 * Why the existing suite was green throughout: AmountsTest only exercised
 * amounts with decimal parts (Rs.2,499.50, INR 333.33, Rs.45,000.00). No
 * existing test used an integer rupee amount. The failure mode was invisible
 * until a real message with "Rs.5" arrived in production.
 *
 * This test uses the exact message body from the incident log so that:
 *   (a) it would have failed before the Amounts.java fix, and
 *   (b) it passes after, proving the fix is in place.
 */
class IncidentINC20260911Test {

    /**
     * The exact message body from incident/app.log and corpus-a.jsonl (m-00022).
     * The transaction amount is ₹5. The available balance is ₹92,213.10.
     */
    private static final String INCIDENT_BODY =
            "Rs.5 debited from a/c **4821 on 04-07-26 at 11:54 to UPI/WATER CAN. "
                    + "Avl Bal: Rs.92,213.10. Not you? Call 18002586161";

    @Test
    @DisplayName("INC-2026-09-11: integer amount Rs.5 is not confused with Avl Bal Rs.92,213.10")
    void integerAmountIsNotConfusedWithAvlBal() {
        // Before the fix: Amounts.first() returned 92213.10 (the balance).
        // After the fix:  it must return 5.00 (the transaction amount).
        BigDecimal amount = Amounts.first(INCIDENT_BODY);

        assertNotNull(amount, "Amounts.first() must not return null for 'Rs.5'");
        assertEquals(new BigDecimal("5.00"), amount,
                "Transaction amount must be 5.00, not the Avl Bal 92213.10");
    }

    @Test
    @DisplayName("INC-2026-09-11: stated balance is still correctly extracted as 92213.10")
    void statedBalanceIsStillExtracted() {
        // The balance extraction must still work — we use it for reconciliation.
        BigDecimal balance = Amounts.statedBalance(INCIDENT_BODY);

        assertNotNull(balance, "statedBalance must not be null");
        assertEquals(new BigDecimal("92213.10"), balance,
                "Avl Bal must be 92213.10");
    }

    @Test
    @DisplayName("INC-2026-09-11: full parser produces amount=5.00, not 92213.10")
    void fullParserProducesCorrectAmount() {
        RawMessage msg = new RawMessage(
                "m-00022-incident-test",
                "sms",
                HdfcSmsParser.SENDER,
                OffsetDateTime.parse("2026-07-04T11:56:00+05:30"),
                "dev-test",
                INCIDENT_BODY);

        Optional<ParsedTxn> result = new HdfcSmsParser().parse(msg);

        assertTrue(result.isPresent(), "HdfcSmsParser must parse this message");
        ParsedTxn txn = result.get();
        assertEquals("4821",              txn.accountLast4());
        assertEquals(Direction.DEBIT,     txn.direction());
        assertEquals(new BigDecimal("5.00"), txn.amount(),
                "Amount must be 5.00 — before the fix it was 92213.10");
        assertEquals("UPI/WATER CAN",     txn.merchant());
        assertEquals(new BigDecimal("92213.10"), txn.statedBalance(),
                "statedBalance must still be the Avl Bal figure");
    }

    @Test
    @DisplayName("INC-2026-09-11: other integer formats also parse correctly")
    void otherIntegerFormatsParseCorrectly() {
        // Rs.20, Rs 99, INR 100, INR 2,750 — all seen in the corpus.
        assertEquals(new BigDecimal("20.00"),   Amounts.first("Rs.20 debited. Avl Bal: Rs.500.00"));
        assertEquals(new BigDecimal("99.00"),   Amounts.first("Rs 99 debited. Avl Bal: Rs.400.00"));
        assertEquals(new BigDecimal("100.00"),  Amounts.first("INR 100 debited. Avl Bal: Rs.300.00"));
        assertEquals(new BigDecimal("2750.00"), Amounts.first(
                "Dear Customer, Acct XX9075 is debited with INR 2,750 on 07/07/2026 16:48. "
                        + "Info: APOLLO PHARMACY. Avl Bal Rs.54,797.58 -ICICI Bank"));
    }
}
