package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.RawMessage;
import java.util.List;
import java.util.Optional;

/** Picks the parser for a message. Add yours here. */
public final class Parsers {

    private final List<MessageParser> parsers;

    public Parsers() {
        // EmailParser is listed first so that email-channel messages are handled
        // before the SMS parsers, which only match on their specific senders.
        this(List.of(new EmailParser(), new HdfcSmsParser(), new IciciSmsParser()));
    }

    public Parsers(List<MessageParser> parsers) {
        this.parsers = List.copyOf(parsers);
    }

    public Optional<ParsedTxn> parse(RawMessage m) {
        for (MessageParser p : parsers) {
            if (p.supports(m)) return p.parse(m);
        }
        return Optional.empty();
    }
}
