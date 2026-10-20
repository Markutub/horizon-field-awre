package dev.awre.protocol;

/** A malformed, oversized, unsupported or hostile message. Never carries user data beyond a short reason. */
public final class ProtocolException extends RuntimeException {
    public enum Reason {
        TOO_LARGE, TRUNCATED, TRAILING_BYTES, BAD_MAGIC, BAD_FRAME_VERSION, BAD_CRC, UNKNOWN_TYPE, BAD_FIELD,
        UNSUPPORTED_ENCODING, INFLATE_LIMIT, CHECKSUM_MISMATCH, OUT_OF_RANGE
    }

    private static final long serialVersionUID = 1L;
    private final Reason reason;

    public ProtocolException(Reason reason, String detail) {
        super(reason + ": " + detail, null, false, false); // no stack trace: hostile input must be cheap to reject
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
