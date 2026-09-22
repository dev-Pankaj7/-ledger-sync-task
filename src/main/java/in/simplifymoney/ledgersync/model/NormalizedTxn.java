package in.simplifymoney.ledgersync.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * ============================ FROZEN - DO NOT EDIT ==========================
 *
 * One real transaction. This is the output contract. Your service is graded on
 * the JSON produced from these fields, so changing the shape means your
 * submission cannot be scored.
 *
 * You may add whatever you like BEHIND this type. You may not change the type.
 *
 *   accountLast4      last four digits of the account the money moved on
 *   occurredAt        when the bank says the transaction happened, in IST.
 *                     NOT when the message arrived
 *   direction         DEBIT or CREDIT
 *   amount            exactly two decimal places, always positive
 *   category          see Category
 *   merchant          whatever the bank called the other side. Not graded
 *   sourceMessageIds  every RawMessage.messageId that evidences this one
 *                     transaction, sorted. One transaction can have several
 *
 * ===========================================================================
 */
public record NormalizedTxn(
        String accountLast4,
        OffsetDateTime occurredAt,
        Direction direction,
        BigDecimal amount,
        Category category,
        String merchant,
        List<String> sourceMessageIds) {

    public NormalizedTxn {
        Objects.requireNonNull(accountLast4, "accountLast4");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(sourceMessageIds, "sourceMessageIds");

        if (accountLast4.length() != 4 || !accountLast4.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("accountLast4 must be 4 digits: " + accountLast4);
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        if (amount.scale() != 2) {
            throw new IllegalArgumentException(
                    "amount must carry exactly 2 decimal places: " + amount.toPlainString());
        }
        if (sourceMessageIds.isEmpty()) {
            throw new IllegalArgumentException("a transaction must cite at least one message");
        }
        sourceMessageIds = List.copyOf(sourceMessageIds);
        merchant = merchant == null ? "" : merchant;
    }
}
