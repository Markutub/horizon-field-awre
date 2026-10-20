package dev.horizonfield.core.stream;

import java.util.function.LongSupplier;

/** Token bucket measured in BYTES (the per-message TokenBucket in awre-protocol counts messages, which hides tile size). */
public final class ByteBudget {
    private final double capacity, refillPerNano;
    private final LongSupplier nanos;
    private double tokens;
    private long last;

    public ByteBudget(long burstBytes, double bytesPerSecond, LongSupplier nanos) {
        if (burstBytes < 1 || bytesPerSecond <= 0) throw new IllegalArgumentException("budget");
        this.capacity = burstBytes; this.refillPerNano = bytesPerSecond / 1e9; this.nanos = nanos;
        this.tokens = burstBytes; this.last = nanos.getAsLong();
    }

    private void refill() {
        long now = nanos.getAsLong();
        tokens = Math.min(capacity, tokens + Math.max(0, now - last) * refillPerNano);
        last = now;
    }

    /** Bytes that may be sent right now. */
    public synchronized long available() { refill(); return (long) tokens; }

    /** Give back bytes taken for a send that did not happen. */
    public synchronized void refund(long bytes) { tokens = Math.min(capacity, tokens + Math.max(0, bytes)); }

    public synchronized boolean tryTake(long bytes) {
        refill();
        if (bytes > tokens) return false;
        tokens -= bytes;
        return true;
    }
}
