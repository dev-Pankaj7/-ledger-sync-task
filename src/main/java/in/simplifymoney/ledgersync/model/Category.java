package in.simplifymoney.ledgersync.model;

/**
 * FROZEN - do not add, remove or rename constants.
 *
 * SPEND    money that left the user and is gone
 * INCOME   money that arrived and is theirs
 * MICRO    a small UPI spend (see the assignment for the threshold). Still
 *          spending, but reported as one rolled-up line rather than individually
 * TRANSFER a leg of the user moving their own money between their own accounts.
 *          Real, but it is neither spending nor income
 */
public enum Category { SPEND, INCOME, MICRO, TRANSFER }
