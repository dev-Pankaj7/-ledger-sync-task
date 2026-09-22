package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction-alert emails — HDFC (alerts@hdfcbank.net) and
 * ICICI (alerts@icicibank.com) both use the same body shape:
 *
 *   Date: Wed, 01 Jul 2026 09:02:00 +0530
 *   Subject: Transaction alert on your account
 *
 *   Dear Customer,
 *
 *   Your account ending 4821 has been debited with INR 99.99.
 *   Merchant / Remarks: IRCTC
 *   Transaction reference: 8085121323
 *
 * The Date header carries the BANK transaction time (not the received_at
 * envelope time). We parse it with its own offset and then express the result
 * in IST (+05:30), exactly as the SMS parsers do.
 *
 * The amount in the body may use "INR" or "Rs." and may or may not have a
 * decimal part (e.g. "INR 45,000" vs "INR 45,000.00" vs "Rs.2,750").
 * Amounts.first() handles all of these after the Amounts fix.
 */
public final class EmailParser implements MessageParser {

    // RFC 2822 date format as banks write it, e.g. "Wed, 01 Jul 2026 09:02:00 +0530"
    // The day-of-week prefix is optional so we try both.
    private static final DateTimeFormatter RFC_WITH_DOW =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);
    private static final DateTimeFormatter RFC_NO_DOW =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    // "Date: Wed, 01 Jul 2026 09:02:00 +0530"
    private static final Pattern DATE_HEADER = Pattern.compile(
            "^Date:\\s*(.+)$", Pattern.MULTILINE);

    // "Your account ending 4821 has been debited with INR 99.99."
    // or "Your account ending 4821 has been credited with Rs.45,000."
    private static final Pattern BODY_LINE = Pattern.compile(
            "Your account ending (\\d{4}) has been (debited|credited) with");

    // "Merchant / Remarks: IRCTC"
    private static final Pattern MERCHANT_LINE = Pattern.compile(
            "Merchant / Remarks:\\s*(.+)");

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();

        // ── 1. Parse the Date header to get the bank transaction time ──────────
        Matcher dateM = DATE_HEADER.matcher(body);
        if (!dateM.find()) return Optional.empty();
        OffsetDateTime occurredAt = parseEmailDate(dateM.group(1).trim());
        if (occurredAt == null) return Optional.empty();
        // Normalise to IST — all output timestamps are in IST.
        occurredAt = occurredAt.withOffsetSameInstant(Dates.IST);

        // ── 2. Account and direction ───────────────────────────────────────────
        Matcher bodyM = BODY_LINE.matcher(body);
        if (!bodyM.find()) return Optional.empty();
        String accountLast4 = bodyM.group(1);
        Direction direction = "debited".equals(bodyM.group(2))
                ? Direction.DEBIT : Direction.CREDIT;

        // ── 3. Amount — use Amounts.first() on the body text after the         ──
        //       Date header so we don't accidentally match a figure in the       ──
        //       header line itself.                                               ──
        BigDecimal amount = Amounts.first(body);
        if (amount == null) return Optional.empty();

        // ── 4. Merchant (best-effort, not graded) ────────────────────────────
        Matcher merchantM = MERCHANT_LINE.matcher(body);
        String merchant = merchantM.find() ? merchantM.group(1).trim() : "";

        return Optional.of(new ParsedTxn(
                accountLast4, occurredAt, direction, amount, merchant,
                null,   // emails don't quote a running balance
                m.messageId()));
    }

    private static OffsetDateTime parseEmailDate(String raw) {
        for (DateTimeFormatter fmt : new DateTimeFormatter[]{RFC_WITH_DOW, RFC_NO_DOW}) {
            try {
                return OffsetDateTime.parse(raw, fmt);
            } catch (DateTimeParseException ignored) {
                // try next format
            }
        }
        return null;
    }
}
