package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ============================ FROZEN - DO NOT EDIT ==========================
 *
 * This test guards the output contract your submission is scored against.
 * If you change it, your submission cannot be scored.
 *
 * If you believe a rule here is wrong, do not edit it - say so in your decision
 * log and email us.
 *
 * ===========================================================================
 */
class NormalizedTxnContractTest {

    private static final OffsetDateTime WHEN =
            OffsetDateTime.parse("2026-07-04T20:24:00+05:30");

    private static NormalizedTxn txn(String amount) {
        return new NormalizedTxn("4821", WHEN, Direction.DEBIT, new BigDecimal(amount),
                Category.SPEND, "AMAZON PAY", List.of("m-1"));
    }

    @Test
    @DisplayName("amount carries exactly two decimal places")
    void amountScaleIsExactlyTwo() {
        assertEquals(2, txn("2499.50").amount().scale());
        assertThrows(IllegalArgumentException.class, () -> txn("2499.5"));
        assertThrows(IllegalArgumentException.class, () -> txn("2499"));
        assertThrows(IllegalArgumentException.class, () -> txn("2499.500"));
    }

    @Test
    @DisplayName("amount is always positive - direction carries the sign")
    void amountIsPositive() {
        assertThrows(IllegalArgumentException.class, () -> txn("-2499.50"));
        assertThrows(IllegalArgumentException.class, () -> txn("0.00"));
    }

    @Test
    @DisplayName("accountLast4 is exactly four digits")
    void accountLast4IsFourDigits() {
        assertThrows(IllegalArgumentException.class,
                () -> new NormalizedTxn("482", WHEN, Direction.DEBIT,
                        new BigDecimal("10.00"), Category.SPEND, "X", List.of("m-1")));
        assertThrows(IllegalArgumentException.class,
                () -> new NormalizedTxn("XX21", WHEN, Direction.DEBIT,
                        new BigDecimal("10.00"), Category.SPEND, "X", List.of("m-1")));
    }

    @Test
    @DisplayName("a transaction must cite at least one source message")
    void mustCiteItsEvidence() {
        assertThrows(IllegalArgumentException.class,
                () -> new NormalizedTxn("4821", WHEN, Direction.DEBIT,
                        new BigDecimal("10.00"), Category.SPEND, "X", List.of()));
    }

    @Test
    @DisplayName("occurredAt keeps its offset - it is not silently normalised to UTC")
    void occurredAtKeepsItsOffset() {
        assertEquals(WHEN.getOffset(), txn("10.00").occurredAt().getOffset());
        assertEquals(20, txn("10.00").occurredAt().getHour());
    }

    @Test
    @DisplayName("every category in the enum is one the reports must handle")
    void categoriesAreStable() {
        assertEquals(List.of(Category.SPEND, Category.INCOME, Category.MICRO,
                Category.TRANSFER), List.of(Category.values()));
    }
}
