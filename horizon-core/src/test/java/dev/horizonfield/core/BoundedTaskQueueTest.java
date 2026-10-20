package dev.horizonfield.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BoundedTaskQueueTest {
    @Test
    void pollsMostUrgentFirstAndDedups() {
        BoundedTaskQueue<String> q = new BoundedTaskQueue<>(10);
        q.offer("a", 5, "A");
        q.offer("b", 1, "B");
        assertEquals(BoundedTaskQueue.Offer.REPLACED, q.offer("a", 0.5, "A2"));
        assertEquals(2, q.size());
        assertEquals("A2", q.pollNow());
        assertEquals("B", q.pollNow());
        assertNull(q.pollNow());
    }

    @Test
    void fullQueueEvictsLeastUrgentOrRejects() {
        BoundedTaskQueue<String> q = new BoundedTaskQueue<>(2);
        q.offer("a", 10, "A");
        q.offer("b", 20, "B");
        assertEquals(BoundedTaskQueue.Offer.REJECTED, q.offer("c", 30, "C"));
        assertEquals(BoundedTaskQueue.Offer.EVICTED_OTHER, q.offer("d", 5, "D"));
        assertEquals("D", q.pollNow());
        assertEquals("A", q.pollNow());
        assertNull(q.pollNow());
    }

    @Test
    void cancelIfRemovesMatchingKeys() {
        BoundedTaskQueue<Integer> q = new BoundedTaskQueue<>(10);
        for (int i = 0; i < 6; i++) q.offer(i, i, i);
        assertEquals(3, q.cancelIf(k -> ((Integer) k) % 2 == 0));
        assertEquals(3, q.size());
    }

    @Test
    void drainRespectsMaxItems() {
        BoundedTaskQueue<Integer> q = new BoundedTaskQueue<>(10);
        for (int i = 0; i < 6; i++) q.offer(i, i, i);
        int[] seen = {0};
        assertEquals(4, q.drain(x -> seen[0]++, 1_000_000_000L, 4));
        assertEquals(2, q.size());
    }
}
