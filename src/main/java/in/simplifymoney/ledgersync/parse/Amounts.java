package in.simplifymoney.ledgersync.parse;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rupee amounts as banks write them.
 *
 * Handles the prefixes we see in practice - "Rs.", "Rs ", "INR " - and strips
 * the thousands separators before handing back a BigDecimal.
 *
 * KEY RULE: the balance marker (Avl Bal, BalAvl, Available Balance, Avl Limit)
 * MUST be stripped from the string before the transaction amount is extracted,
 * so that an integer transaction amount like "Rs.5" is never confused with the
 * balance figure that follows it.
 *
 * INC-2026-09-11 root cause: "Rs.5" has no decimal part. The old AMOUNT
 * pattern required ".[0-9]{2}", so it did not match "Rs.5" and fell through to
 * match "Rs.92,213.10" in the Avl Bal clause instead.  Fix: allow optional
 * decimal part, and strip the balance clause before searching.
 */
public final class Amounts {

    private Amounts() {}

    /**
     * Matches a rupee amount with optional decimal part.
     * Examples matched: Rs.5  Rs 99  INR 100  Rs.2,750  Rs.2,499.50  INR 45,000.00
     */
    private static final Pattern AMOUNT =
            Pattern.compile("(?:Rs\\.?|INR)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)");

    /**
     * Matches the balance/limit clause that banks append after the transaction
     * amount.  Everything from this marker to end-of-string is stripped before
     * we search for the transaction amount, so an integer txn amount can never
     * be confused with the (always decimal) balance figure.
     */
    private static final Pattern BALANCE_CLAUSE = Pattern.compile(
            "(?:Avl\\s*Bal|Available\\s*Balance|BalAvl|Avl\\s*Limit)\\s*:?\\s*"
                    + "(?:Rs\\.?|INR)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)",
            Pattern.CASE_INSENSITIVE);

    /** The transaction amount: the first rupee figure before any balance clause. */
    public static BigDecimal first(String body) {
        // Strip the balance clause so we never confuse it with the txn amount.
        String stripped = BALANCE_CLAUSE.matcher(body).replaceFirst("");
        Matcher m = AMOUNT.matcher(stripped);
        if (!m.find()) return null;
        return toDecimal(m.group(1));
    }

    /** The balance the bank quoted, if it quoted one. */
    public static BigDecimal statedBalance(String body) {
        Matcher m = BALANCE_CLAUSE.matcher(body);
        if (!m.find()) return null;
        return toDecimal(m.group(1));
    }

    private static BigDecimal toDecimal(String raw) {
        // Normalise to exactly 2 decimal places for the NormalizedTxn contract.
        String stripped = raw.replace(",", "");
        BigDecimal bd = new BigDecimal(stripped);
        return bd.setScale(2);
    }
}
