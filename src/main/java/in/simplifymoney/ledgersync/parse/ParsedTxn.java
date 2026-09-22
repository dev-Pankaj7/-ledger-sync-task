package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * What a single message says, before anything has been decided about it.
 *
 * statedBalance is the account balance the bank quoted in the message, when it
 * quoted one. It may be null.
 */
public record ParsedTxn(
        String accountLast4,
        OffsetDateTime occurredAt,
        Direction direction,
        BigDecimal amount,
        String merchant,
        BigDecimal statedBalance,
        String sourceMessageId) {
}
