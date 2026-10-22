package dev.horizonfield.core;

import java.util.Arrays;

/**
 * Bounded timing recorder: keeps the last {@code capacity} samples (nanoseconds) in a ring buffer plus an all-time count and
 * maximum. Percentiles are over the retained window only. Memory is fixed (8 * capacity bytes); thread-safe via synchronization.
 * Used to measure main-thread capture cost on a real server; it adds no behaviour of its own.
 */
public final class TimingWindow {
    private final long[] ring;
    private int next;
    private int size;
    private long total;
    private long maxNanos;

    public TimingWindow(int capacity) {
        if (capacity < 1 || capacity > 1 << 20) throw new IllegalArgumentException("capacity " + capacity);
        this.ring = new long[capacity];
    }

    public synchronized void record(long nanos) {
        if (nanos < 0) nanos = 0;
        ring[next] = nanos;
        next = (next + 1) % ring.length;
        if (size < ring.length) size++;
        total++;
        if (nanos > maxNanos) maxNanos = nanos;
    }

    /** Snapshot of the window: samples ever recorded, retained samples, p50/p95/p99 over the retained window, all-time max (ns). */
    public synchronized Summary summary() {
        if (size == 0) return new Summary(total, 0, 0, 0, 0, maxNanos);
        long[] c = Arrays.copyOf(ring, size);
        Arrays.sort(c);
        return new Summary(total, size, at(c, 0.50), at(c, 0.95), at(c, 0.99), maxNanos);
    }

    /** Nearest-rank percentile of a sorted array. */
    private static long at(long[] sorted, double p) {
        int rank = (int) Math.ceil(p * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    public record Summary(long total, int window, long p50, long p95, long p99, long max) {
        /** e.g. {@code n=120 p50=0.04ms p95=0.31ms p99=0.52ms max=0.60ms}; ms with two decimals. */
        public String format() {
            return String.format(java.util.Locale.ROOT, "n=%d p50=%.2fms p95=%.2fms p99=%.2fms max=%.2fms",
                    total, p50 / 1e6, p95 / 1e6, p99 / 1e6, max / 1e6);
        }
    }
}
