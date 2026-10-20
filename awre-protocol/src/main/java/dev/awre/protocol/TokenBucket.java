package dev.awre.protocol;

import java.util.function.LongSupplier;

/** Token bucket with an injectable nanosecond clock (deterministic in tests). Not thread-safe by itself. */
public final class TokenBucket {
    private final double capacity, refillPerNano;
    private final LongSupplier nanos;
    private double tokens;
    private long last;

    public TokenBucket(int capacity, double refillPerSecond, LongSupplier nanos) {
        this.capacity = capacity; this.refillPerNano = refillPerSecond / 1e9; this.nanos = nanos;
        this.tokens = capacity; this.last = nanos.getAsLong();
    }

    public boolean tryAcquire() {
        long now = nanos.getAsLong();
        tokens = Math.min(capacity, tokens + Math.max(0, now - last) * refillPerNano);
        last = now;
        if (tokens >= 1.0) { tokens -= 1.0; return true; }
        return false;
    }
}
