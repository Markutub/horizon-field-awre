package dev.horizonfield.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CaptureBacklogTest {
    /** Premise of the defect: the bounded queue alone silently forgets requests once it is full. */
    @Test void aBoundedQueueAloneLosesRequestsBeyondItsCapacity() {
        BoundedTaskQueue<Integer> q = new BoundedTaskQueue<>(4096);
        int rejectedOrEvicted = 0;
        for (int i = 0; i < 4761; i++) {                       // 69 x 69 loaded chunks, offered in distance order (nearest first)
            BoundedTaskQueue.Offer o = q.offer(i, i, i);
            if (o == BoundedTaskQueue.Offer.REJECTED || o == BoundedTaskQueue.Offer.EVICTED_OTHER) rejectedOrEvicted++;
        }
        assertEquals(4096, q.size());
        assertEquals(665, rejectedOrEvicted, "665 chunks (the farthest = the horizon edge) would never be observed");
    }

    @Test void noRequestIsLostWhenMoreThanTheQueueCapacityIsRequested() {
        CaptureBacklog<Integer> b = new CaptureBacklog<>(4096);
        for (int i = 0; i < 4761; i++) b.request(i);
        Map<Integer, Integer> handled = new HashMap<>();
        int rounds = 0;
        while (!b.idle() && rounds++ < 100_000) {
            b.pump(k -> k);                                     // priority = the key itself (smaller = nearer)
            b.drain(k -> { handled.merge(k, 1, Integer::sum); return true; }, 1_000_000_000L, 40);   // 2 chunks/tick at 20 tps, here 40 per round
        }
        assertTrue(b.idle());
        assertEquals(4761, handled.size(), "every requested chunk must be handled");
        assertTrue(handled.values().stream().allMatch(c -> c == 1), "and exactly once");
        assertEquals(4761, b.stats().handled());
        assertTrue(b.stats().maxWaiting() >= 665);
    }

    @Test void nearestFirstWithinWhatFitsAndPrioritiesAreRecomputedAtPumpTime() {
        CaptureBacklog<Integer> b = new CaptureBacklog<>(8);
        for (int i = 0; i < 40; i++) b.request(i);
        double[] pos = {1000};                                  // the "player" moves: priority = distance to pos
        b.pump(k -> Math.abs(k - pos[0]));                      // room 8 >= capacity/4: the 8 nearest to 1000 = 32..39
        Set<Integer> first = new HashSet<>();
        b.drain(k -> { first.add(k); return true; }, 1_000_000_000L, 100);
        assertEquals(Set.of(32, 33, 34, 35, 36, 37, 38, 39), first);
        pos[0] = 0;                                             // the player teleported
        b.pump(k -> Math.abs(k - pos[0]));
        Set<Integer> second = new HashSet<>();
        b.drain(k -> { second.add(k); return true; }, 1_000_000_000L, 100);
        assertEquals(Set.of(0, 1, 2, 3, 4, 5, 6, 7), second, "stale priorities from request time must not be used");
    }

    @Test void aHandlerThatCannotFinishGetsTheKeyAgainAndAThrowingHandlerLosesNothing() {
        CaptureBacklog<String> b = new CaptureBacklog<>(16);
        b.request("a"); b.request("b");
        int[] tries = {0};
        for (int round = 0; round < 5 && !b.idle(); round++) {
            b.pump(k -> 0);
            b.drain(k -> k.equals("a") && ++tries[0] < 3 ? false : true, 1_000_000_000L, 10);   // "a" fails twice, then succeeds
        }
        assertTrue(b.idle());
        assertEquals(3, tries[0]);
        assertEquals(2, b.stats().retried());
        b.request("c");
        b.pump(k -> 0);
        assertThrows(IllegalStateException.class, () -> b.drain(k -> { throw new IllegalStateException("boom"); }, 1_000_000_000L, 10));
        assertEquals(1, b.waiting(), "the key whose handler threw is waiting again, not lost");
    }

    @Test void duplicateRequestsAreCoalescedAndAQueuedKeyIsNotDuplicated() {
        CaptureBacklog<Integer> b = new CaptureBacklog<>(16);
        for (int i = 0; i < 1000; i++) b.request(7);            // a burst of events for the same chunk
        assertEquals(1, b.waiting());
        b.pump(k -> 0);
        b.request(7);                                           // already queued: stays a single entry
        assertEquals(0, b.waiting());
        assertEquals(1, b.queued());
        int[] n = {0};
        b.drain(k -> { n[0]++; return true; }, 1_000_000_000L, 10);
        assertEquals(1, n[0]);
        b.request(7);                                           // a change after it was handled must be observed again
        b.pump(k -> 0);
        b.drain(k -> { n[0]++; return true; }, 1_000_000_000L, 10);
        assertEquals(2, n[0]);
    }

    @Test void drainRespectsTheItemAndTimeBudgets() {
        CaptureBacklog<Integer> b = new CaptureBacklog<>(64);
        for (int i = 0; i < 50; i++) b.request(i);
        b.pump(k -> k);
        assertEquals(2, b.drain(k -> true, 1_000_000_000L, 2));
        assertEquals(0, b.drain(k -> true, 0L, 100), "a zero time budget handles nothing");
        assertEquals(48, b.queued());
    }
}
