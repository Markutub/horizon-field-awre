package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.*;

import dev.awre.protocol.ClientSession;
import dev.awre.protocol.Provenance;
import dev.awre.protocol.TileKey;
import dev.horizonfield.core.Confidence;
import dev.horizonfield.core.lod.LodKey;
import dev.horizonfield.core.terrain.LodTile;
import dev.horizonfield.core.terrain.LodTileCodec;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Time from "all tiles received" to "all candidate tiles meshed" must not be set by the build quota. The CI client built exactly 40 tiles/s
 * (maxInFlight 4 x one 10 Hz update each) and was still meshing 40 s after the stream had finished.
 */
class MeshThroughputTest {
    private static final String DIM = "minecraft:overworld";

    private static final class Store implements ClientHorizonView.TileSource {
        final Map<TileKey, ClientSession.Received> tiles = new HashMap<>();
        @Override public Set<TileKey> keys() { return new HashSet<>(tiles.keySet()); }
        @Override public ClientSession.Received tile(TileKey k) { return tiles.get(k); }
        @Override public List<String> palette(TileKey k) { return List.of("minecraft:air", "minecraft:stone"); }
        void put(int tx, int tz) {
            short[] top = new short[256], mat = new short[256];
            java.util.Arrays.fill(top, (short) 64); java.util.Arrays.fill(mat, (short) 1);
            tiles.put(new TileKey(1, 2, DIM, tx, tz, 0), new ClientSession.Received(1, Provenance.values()[0], LodTileCodec.encode(new LodTile(new LodKey(0, tx, tz), top, mat, Confidence.OBSERVED))));
        }
    }

    /** Updates (each = 100 ms of game time at 10 Hz) needed until every candidate is meshed; the queue drains between updates like the mesher thread does. */
    private static int updatesToMeshEverything(Store s, int maxInFlight) {
        ArrayDeque<Runnable> q = new ArrayDeque<>();
        try (ClientHorizonView v = new ClientHorizonView(s, 4096, maxInFlight, q::add)) {
            for (int updates = 1; updates <= 10_000; updates++) {
                v.update(0, 0, 0, 1e9, null);
                assertTrue(q.size() <= maxInFlight, "the queue stays bounded: " + q.size());
                while (!q.isEmpty()) q.poll().run();
                if (v.meshedKeys().size() >= s.tiles.size()) return updates;
            }
            fail("never finished");
            return -1;
        }
    }

    @Test void oneUpdateQueuesExactlyTheDefaultNumberOfJobsAndNoMore() {
        Store s = new Store();
        for (int x = 0; x < 40; x++) for (int z = 0; z < 40; z++) s.put(10 + x, z);       // 1600 candidates
        ArrayDeque<Runnable> q = new ArrayDeque<>();
        try (ClientHorizonView v = new ClientHorizonView(s, 4096, ClientHorizonView.DEFAULT_MAX_IN_FLIGHT, q::add)) {
            v.update(0, 0, 0, 1e9, null);
            assertEquals(ClientHorizonView.DEFAULT_MAX_IN_FLIGHT, q.size());
        }
    }

    @Test void aFullHorizonIsMeshedWithinFiveSecondsOfGameTimeNotForty() {
        Store s = new Store();
        for (int x = 0; x < 40; x++) for (int z = 0; z < 40; z++) s.put(10 + x, z);       // 1600 tiles, about the CI scenario (1620 candidates)
        int slow = updatesToMeshEverything(s, 4);                                           // the former default
        int fast = updatesToMeshEverything(s, ClientHorizonView.DEFAULT_MAX_IN_FLIGHT);
        assertEquals(400, slow, "model check against the CI: 1600 tiles / (4 per update) = 400 updates = 40 s");
        assertEquals(25, fast);
        assertTrue(fast <= 50, "at 10 updates per second that is " + fast / 10.0 + " s");
    }

    @Test void theDefaultIsNotLoweredAgainBelowWhatMakesTheHorizonAppearQuickly() {
        assertTrue(ClientHorizonView.DEFAULT_MAX_IN_FLIGHT >= 32, "below 32 a 1600-tile horizon takes more than 5 s at 10 Hz");
    }
}
