package dev.horizonfield.core;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Bounded, de-duplicating priority queue. Lower priority value = more urgent (e.g. squared distance to nearest player).
 * Offering an existing key replaces its payload/priority. When full, the least urgent entry is evicted if the new one is
 * more urgent, otherwise the offer is rejected (backpressure).
 */
public final class BoundedTaskQueue<T> {
    public enum Offer { ADDED, REPLACED, EVICTED_OTHER, REJECTED }

    private record Entry<T>(Object key, double priority, long seq, T payload) { }

    private final int capacity;
    private final Map<Object, Entry<T>> byKey = new HashMap<>();
    private final TreeSet<Entry<T>> ordered = new TreeSet<>((a, b) -> {
        int c = Double.compare(a.priority, b.priority);
        return c != 0 ? c : Long.compare(a.seq, b.seq);
    });
    private long seq;
    private boolean closed;

    public BoundedTaskQueue(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }

    public synchronized Offer offer(Object key, double priority, T payload) {
        if (closed) return Offer.REJECTED;
        Entry<T> existing = byKey.get(key);
        Offer result = Offer.ADDED;
        if (existing != null) {
            ordered.remove(existing);
            byKey.remove(key);
            result = Offer.REPLACED;
        } else if (byKey.size() >= capacity) {
            Entry<T> worst = ordered.last();
            if (worst.priority <= priority) return Offer.REJECTED;
            ordered.remove(worst);
            byKey.remove(worst.key);
            result = Offer.EVICTED_OTHER;
        }
        Entry<T> e = new Entry<>(key, priority, seq++, payload);
        byKey.put(key, e);
        ordered.add(e);
        notifyAll();
        return result;
    }

    /** Removes and returns the most urgent payload, or null if empty. */
    public synchronized T pollNow() {
        if (ordered.isEmpty()) return null;
        Entry<T> e = ordered.pollFirst();
        byKey.remove(e.key);
        return e.payload;
    }

    /** Blocks up to timeoutMillis for an item. Returns null on timeout or close. */
    public synchronized T poll(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (ordered.isEmpty() && !closed) {
            long left = (deadline - System.nanoTime()) / 1_000_000L;
            if (left <= 0) return null;
            wait(left);
        }
        return pollNow();
    }

    /** Main-thread friendly: runs handlers for urgent items until the time budget is exhausted. Returns items handled. */
    public int drain(Consumer<T> handler, long budgetNanos, int maxItems) {
        long end = System.nanoTime() + budgetNanos;
        int n = 0;
        while (n < maxItems && System.nanoTime() < end) {
            T t = pollNow();
            if (t == null) break;
            handler.accept(t);
            n++;
        }
        return n;
    }

    public synchronized boolean cancel(Object key) {
        Entry<T> e = byKey.remove(key);
        if (e == null) return false;
        ordered.remove(e);
        return true;
    }

    public synchronized int cancelIf(Predicate<Object> keyPredicate) {
        int n = 0;
        for (Object k : java.util.List.copyOf(byKey.keySet())) {
            if (keyPredicate.test(k)) { cancel(k); n++; }
        }
        return n;
    }

    public synchronized int size() { return byKey.size(); }
    public int capacity() { return capacity; }

    public synchronized void close() {
        closed = true;
        byKey.clear();
        ordered.clear();
        notifyAll();
    }
}
