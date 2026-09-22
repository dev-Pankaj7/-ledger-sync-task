package in.simplifymoney.ledgersync.parse;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * Bank SMS carry a local date and time and no timezone. The customer, the bank
 * and the branch are all in India, so these are IST.
 *
 * Formats supported:
 *   dd-MM-yy HH:mm          HDFC V1 (e.g. "04-07-26 07:19")
 *   dd/MM/yyyy HH:mm        ICICI V1 (e.g. "01/07/2026 10:22")
 *   dd MMM yy HH:mm         HDFC V2 (e.g. "24 Jul 26 09:15")
 *   dd-MMM-yyyy HH:mm       ICICI V2 (e.g. "24-Jul-2026 12:28")
 *   dd-MMM-yyyy HH:mm       (also handles full-month names via ENGLISH locale)
 */
public final class Dates {

    private Dates() {}

    public static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

    private static final List<DateTimeFormatter> SMS_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd-MM-yy HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMM yy HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MMM-yy HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm", Locale.ENGLISH));

    /** Parse a local date-time written by a bank, as IST. */
    public static OffsetDateTime ist(String dateAndTime) {
        for (DateTimeFormatter f : SMS_FORMATS) {
            try {
                return LocalDateTime.parse(dateAndTime.trim(), f).atOffset(IST);
            } catch (DateTimeParseException ignored) {
                // try the next shape
            }
        }
        return null;
    }
}
