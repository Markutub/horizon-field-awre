package dev.horizonfield.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Work that must NOT be lost, in front of a {@link BoundedTaskQueue}.
 *
 * <p>The bounded queue alone is lossy by design: when it is full an offer is REJECTED, or evicts (and forgets) the least urgent
 * entry. That is the right behaviour for backpressure, but wrong for "observe this chunk": a chunk whose request was dropped is
 * never observed again (the chunk stays loaded, no event fires) and the distant terrain keeps a permanent hole, which the
 * persistent cache then remembers. Here a request is remembered in {@code waiting} until it has been handled, so nothing is lost;
 * only the number of items in the bounded queue (and therefore the memory held by their payloads) is capped. {@code waiting} holds
 * keys only and is bounded by the number of distinct keys requested, i.e. by the number of loaded chunks.
 *
 * <p>Flow: {@link #request} is O(1) and priority-free (cheap enough to call from any event handler, thousands of times per second);
 * {@link #pump} moves the most urgent waiting keys into the bounded queue using priorities computed NOW (distance to the players'
 * current positions, not where they were when the event fired); {@link #drain} hands queued keys to a handler that reports whether it
 * finished (true) or must retry later (false). Not thread-safe by itself: use from one thread (the Paper main thread).
 */
public final class CaptureBacklog<K> {
    public record Stats(long requested, long handled, long retried, int waiting, int queued, int maxWaiting) { }

    private final BoundedTaskQueue<K> queue;
    private final int capacity;
    private final Set<K> waiting = new LinkedHashSet<>();
    private long requested, handled, retried;
    private int maxWaiting;

    public CaptureBacklog(int queueCapacity) {
        this.queue = new BoundedTaskQueue<>(queueCapacity);
        this.capacity = queueCapacity;
    }

    /** O(1). A key already in the queue is left alone (it will be handled after this change, so it sees the change). */
    public void request(K key) {
        requested++;
        if (queue.contains(key)) return;
        waiting.add(key);
        if (waiting.size() > maxWaiting) maxWaiting = waiting.size();
    }

    /**
     * Moves waiting keys into the bounded queue, nearest (lowest priority value) first. Cheap when the queue has little room: the
     * sort over all waiting keys only runs when at least a quarter of the queue is free, so a long backlog costs one sort per
     * quarter-queue of work, not one per tick.
     */
    public void pump(ToDoubleFunction<K> priority) {
        if (waiting.isEmpty()) return;
        int room = capacity - queue.size();
        if (room <= 0) return;
        if (waiting.size() <= room) {
            for (K k : waiting) queue.offer(k, priority.applyAsDouble(k), k);
            waiting.clear();
            return;
        }
        if (room < Math.max(1, capacity / 4)) return;
        List<K> keys = new ArrayList<>(waiting);
        double[] pr = new double[keys.size()];
        Integer[] idx = new Integer[keys.size()];
        for (int i = 0; i < keys.size(); i++) { pr[i] = priority.applyAsDouble(keys.get(i)); idx[i] = i; }
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(pr[a], pr[b]));
        for (int n = 0; n < room; n++) {
            K k = keys.get(idx[n]);
            queue.offer(k, pr[idx[n]], k);
            waiting.remove(k);
        }
    }

    /**
     * Handles queued keys, most urgent first, until the time budget or {@code maxItems} is used up.
     * @param handler returns true when the key is fully handled (or no longer relevant), false to retry it later
     * @return the number of keys the handler was called for
     */
    public int drain(Predicate<K> handler, long budgetNanos, int maxItems) {
        long end = System.nanoTime() + budgetNanos;
        return drain(handler, () -> System.nanoTime() < end, maxItems);
    }

    /** Same, but the caller decides before each key whether another may start (for example {@link DrainGovernor#mayStart}). */
    public int drain(Predicate<K> handler, java.util.function.BooleanSupplier mayStart, int maxItems) {
        int n = 0;
        while (n < maxItems && mayStart.getAsBoolean()) {
            K k = queue.pollNow();
            if (k == null) break;
            n++;
            boolean done;
            try { done = handler.test(k); }
            catch (RuntimeException e) { waiting.add(k); retried++; throw e; }   // never lose the key because a handler threw
            if (done) handled++; else { waiting.add(k); retried++; }
        }
        return n;
    }

    public int waiting() { return waiting.size(); }
    public int queued() { return queue.size(); }
    public boolean idle() { return waiting.isEmpty() && queue.size() == 0; }
    public Stats stats() { return new Stats(requested, handled, retried, waiting.size(), queue.size(), maxWaiting); }

    /** Forgets everything (plugin shutdown). */
    public void close() { queue.close(); waiting.clear(); }
}
