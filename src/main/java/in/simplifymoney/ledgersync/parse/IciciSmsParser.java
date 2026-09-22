package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ICICI Bank SMS — three shapes are in production.
 *
 * V1 (original):
 *   "Dear Customer, Acct XX9075 is debited with INR 22.50 on 01/07/2026 10:22.
 *    Info: UPI/VEGETABLE VENDOR. Avl Bal Rs.31,882.25 -ICICI Bank"
 *   Also handles "Rs.X" amounts (not just INR).
 *
 * V2 (newer compact format):
 *   "ICICI Bank Acct XX9075 Dr INR 12.50 on 24-Jul-2026 12:28;
 *    UPI/CHAIWALA ref no 117078760588. BalAvl Rs 52,818.80"
 *   Direction is "Dr" (debit) or "Cr" (credit).
 *   Date uses "dd-MMM-yyyy HH:mm" (e.g. "24-Jul-2026 12:28").
 *   Merchant is the text between the semicolon and "ref no".
 *
 * The phishing sender (VK-ICICIB) is deliberately excluded from supports()
 * — only VM-ICICIB-T is legitimate ICICI Bank.
 */
public final class IciciSmsParser implements MessageParser {

    public static final String SENDER = "VM-ICICIB-T";

    // V1: "Acct XX9075 is debited/credited with ... on dd/MM/yyyy HH:mm. Info: MERCHANT."
    private static final Pattern V1 = Pattern.compile(
            "Acct XX(?<acct>\\d{4}) is (?<dir>debited|credited) with .*? "
                    + "on (?<when>\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2})\\. "
                    + "Info: (?<merchant>[^.]+)\\.");

    // V2: "ICICI Bank Acct XX9075 Dr INR ... on 24-Jul-2026 12:28; MERCHANT ref no ..."
    private static final Pattern V2 = Pattern.compile(
            "ICICI Bank Acct XX(?<acct>\\d{4}) (?<dir>Dr|Cr) .*? "
                    + "on (?<when>\\d{2}-[A-Za-z]+-\\d{4} \\d{2}:\\d{2}); "
                    + "(?<merchant>.+?) ref no ");

    @Override
    public boolean supports(RawMessage m) {
        return "sms".equals(m.channel()) && SENDER.equals(m.sender());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();

        Matcher v1 = V1.matcher(body);
        if (v1.find()) {
            BigDecimal amount = Amounts.first(body);
            OffsetDateTime at = Dates.ist(v1.group("when"));
            if (amount == null || at == null) return Optional.empty();
            Direction d = "debited".equals(v1.group("dir")) ? Direction.DEBIT : Direction.CREDIT;
            return Optional.of(new ParsedTxn(v1.group("acct"), at, d, amount,
                    v1.group("merchant").trim(), Amounts.statedBalance(body), m.messageId()));
        }

        Matcher v2 = V2.matcher(body);
        if (v2.find()) {
            BigDecimal amount = Amounts.first(body);
            OffsetDateTime at = Dates.ist(v2.group("when"));
            if (amount == null || at == null) return Optional.empty();
            Direction d = "Dr".equals(v2.group("dir")) ? Direction.DEBIT : Direction.CREDIT;
            return Optional.of(new ParsedTxn(v2.group("acct"), at, d, amount,
                    v2.group("merchant").trim(), Amounts.statedBalance(body), m.messageId()));
        }

        return Optional.empty();
    }
}
