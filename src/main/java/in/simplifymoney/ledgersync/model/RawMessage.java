package in.simplifymoney.ledgersync.model;

import java.time.OffsetDateTime;

/**
 * One SMS or email exactly as the mobile client uploaded it.
 *
 * messageId is assigned by the client at upload time. It identifies THIS UPLOAD,
 * not the underlying message: the same SMS re-read from the inbox is uploaded
 * again with a new messageId.
 */
public record RawMessage(
        String messageId,
        String channel,          // "sms" | "email"
        String sender,
        OffsetDateTime receivedAt,
        String deviceId,
        String body) {
}
