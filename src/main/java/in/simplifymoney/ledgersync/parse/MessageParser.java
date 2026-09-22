package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.RawMessage;
import java.util.Optional;

public interface MessageParser {

    /** Cheap check: is this message one this parser knows how to read? */
    boolean supports(RawMessage m);

    /**
     * Read the message.
     *
     * Returns empty when the message is not a transaction at all - an OTP, an
     * advert, a balance enquiry. Returning empty is a normal outcome, not an
     * error.
     */
    Optional<ParsedTxn> parse(RawMessage m);
}
