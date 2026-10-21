package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.*;

import dev.awre.protocol.ClientSession;
import dev.awre.protocol.Provenance;
import dev.awre.protocol.TileKey;
import dev.horizonfield.core.Confidence;
import dev.horizonfield.core.lod.LodKey;
import dev.horizonfield.core.terrain.LodTile;
import dev.horizonfield.core.terrain.LodTileCodec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class ClientHorizonViewTest {
    private static final String DIM = "minecraft:overworld";

    /** In-memory tile store with controllable revisions. */
    private static final class Fake implements ClientHorizonView.TileSource {
        final Map<TileKey, ClientSession.Received> tiles = new HashMap<>();
        @Override public Set<TileKey> keys() { return new HashSet<>(tiles.keySet()); }
        @Override public ClientSession.Received tile(TileKey k) { return tiles.get(k); }
        @Override public List<String> palette(TileKey k) { return List.of("minecraft:air", "minecraft:stone"); }
        void put(int lod, int tx, int tz, long rev, int height) {
            short[] top = new short[256], mat = new short[256];
            java.util.Arrays.fill(top, (short) height);
            java.util.Arrays.fill(mat, (short) 1);
            byte[] bytes = LodTileCodec.encode(new LodTile(new LodKey(lod, tx, tz), top, mat, Confidence.OBSERVED));
            tiles.put(key(lod, tx, tz), new ClientSession.Received(rev, Provenance.values()[0], bytes));
        }
        void putCorrupt(int lod, int tx, int tz, long rev) { tiles.put(key(lod, tx, tz), new ClientSession.Received(rev, Provenance.values()[0], new byte[]{9, 9, 9})); }
    }

    private static TileKey key(int lod, int tx, int tz) { return new TileKey(1, 2, DIM, tx, tz, lod); }

    private static Set<TileKey> keys(List<ClientHorizonView.Drawable> l) { Set<TileKey> s = new HashSet<>(); for (var d : l) s.add(d.key()); return s; }

    private static ClientHorizonView sync(Fake f) { return new ClientHorizonView(f, 1000, 1000, Runnable::run); }

    @Test void tilesInsideVanillaOrBeyondMaxDistanceAreNotDrawn() {
        Fake f = new Fake();
        f.put(0, 0, 0, 1, 64);        // x 0..16: fully inside a vanilla radius of 100 around (0,0)
        f.put(0, 10, 0, 1, 64);       // x 160..176: outside vanilla, within max distance
        f.put(0, 100, 0, 1, 64);      // x 1600..: beyond max distance 500
        try (ClientHorizonView v = sync(f)) {
            v.update(0, 0, 100, 500, null);                         // first call schedules builds (synchronously here)
            List<ClientHorizonView.Drawable> d = v.update(0, 0, 100, 500, null);
            assertEquals(Set.of(key(0, 10, 0)), keys(d));
            ClientHorizonView.Stats s = v.stats();
            assertEquals(3, s.held());
            assertEquals(1, s.hiddenByVanilla());
            assertEquals(1, s.tooFar());
            assertEquals(1, s.drawn());
            assertTrue(s.triangles() > 0);
        }
    }

    @Test void aTileStraddlingTheVanillaBorderIsStillDrawn() {
        Fake f = new Fake();
        f.put(0, 6, 0, 1, 64);        // x 96..112, vanilla radius 100 cuts through it
        try (ClientHorizonView v = sync(f)) {
            v.update(0, 0, 100, 500, null);
            assertEquals(1, v.update(0, 0, 100, 500, null).size());
        }
    }

    @Test void parentIsHiddenOnlyWhenAllFourChildrenAreDrawable() {
        Fake f = new Fake();
        f.put(1, 4, 0, 1, 70);        // lod1 tile x 128..160 (size 32); children lod0 tiles (8,0),(9,0),(8,1),(9,1)
        f.put(0, 8, 0, 1, 70); f.put(0, 9, 0, 1, 70); f.put(0, 8, 1, 1, 70);
        try (ClientHorizonView v = sync(f)) {
            v.update(0, 0, 0, 1000, null);
            Set<TileKey> three = keys(v.update(0, 0, 0, 1000, null));
            assertTrue(three.contains(key(1, 4, 0)), "3 of 4 children: the parent must keep covering the hole");
            assertEquals(4, three.size());
            f.put(0, 9, 1, 1, 70);
            v.update(0, 0, 0, 1000, null);
            Set<TileKey> four = keys(v.update(0, 0, 0, 1000, null));
            assertFalse(four.contains(key(1, 4, 0)), "all 4 children ready: parent hidden");
            assertEquals(4, four.size());
            assertEquals(1, v.stats().hiddenByFiner());
        }
    }

    @Test void coverageWorksThroughGrandchildrenAndCountsVanillaCoveredChildren() {
        Fake f = new Fake();
        f.put(2, 1, 0, 1, 70);                                  // lod2 x 64..128 (size 64)
        // children (lod1): (2,0),(3,0),(2,1),(3,1); make (2,0) a node of 4 lod0 grandchildren, others plain lod1
        f.put(1, 3, 0, 1, 70); f.put(1, 2, 1, 1, 70); f.put(1, 3, 1, 1, 70);
        f.put(0, 4, 0, 1, 70); f.put(0, 5, 0, 1, 70); f.put(0, 4, 1, 1, 70); f.put(0, 5, 1, 1, 70);
        try (ClientHorizonView v = sync(f)) {
            v.update(0, 0, 0, 5000, null);
            Set<TileKey> d = keys(v.update(0, 0, 0, 5000, null));
            assertFalse(d.contains(key(2, 1, 0)));
            assertTrue(d.contains(key(1, 3, 0)) && d.contains(key(0, 4, 0)) && d.contains(key(0, 5, 1)));
        }
    }

    @Test void aNewRevisionKeepsDrawingTheOldMeshUntilTheNewOneIsReady() {
        Fake f = new Fake();
        f.put(0, 10, 0, 1, 64);
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        Executor manual = queue::add;
        try (ClientHorizonView v = new ClientHorizonView(f, 100, 10, manual)) {
            assertTrue(v.update(0, 0, 0, 500, null).isEmpty(), "nothing is ready before the worker ran");
            queue.poll().run();
            var first = v.update(0, 0, 0, 500, null);
            assertEquals(1, first.size());
            assertEquals(1, first.get(0).revision());
            f.put(0, 10, 0, 2, 90);                                // the server changed the tile
            var during = v.update(0, 0, 0, 500, null);
            assertEquals(1, during.size());
            assertEquals(1, during.get(0).revision(), "old mesh stays while rebuilding");
            queue.poll().run();
            var after = v.update(0, 0, 0, 500, null);
            assertEquals(2, after.get(0).revision());
            assertTrue(first.get(0).mesh() != after.get(0).mesh());
            assertTrue(queue.isEmpty());
        }
    }

    @Test void droppedTilesDisappearAndCorruptTilesAreRetriedOnlyOnANewRevision() {
        Fake f = new Fake();
        f.put(0, 10, 0, 1, 64);
        f.putCorrupt(0, 11, 0, 1);
        try (ClientHorizonView v = sync(f)) {
            v.update(0, 0, 0, 500, null);
            assertEquals(Set.of(key(0, 10, 0)), keys(v.update(0, 0, 0, 500, null)));
            assertEquals(1, v.stats().failures());
            v.update(0, 0, 0, 500, null);
            assertEquals(1, v.stats().failures(), "same bad revision is not retried every update");
            f.putCorrupt(0, 11, 0, 2);
            v.update(0, 0, 0, 500, null);
            assertEquals(2, v.stats().failures());
            f.tiles.remove(key(0, 10, 0));                           // DROP
            assertTrue(v.update(0, 0, 0, 500, null).isEmpty());
            assertTrue(v.meshedKeys().isEmpty());
        }
    }

    @Test void buildsAreNearestFirstAndBoundedByMaxInFlight() {
        Fake f = new Fake();
        for (int i = 10; i < 20; i++) f.put(0, i, 0, 1, 64);
        List<Runnable> jobs = new ArrayList<>();
        try (ClientHorizonView v = new ClientHorizonView(f, 100, 3, jobs::add)) {
            v.update(0, 0, 0, 5000, null);
            assertEquals(3, jobs.size());
            assertEquals(3, v.stats().pending());
            jobs.forEach(Runnable::run);
            assertEquals(Set.of(key(0, 10, 0), key(0, 11, 0), key(0, 12, 0)), v.meshedKeys(), "the nearest three were scheduled first");
        }
    }

    @Test void worldFilterAndMeshCapAreRespected() {
        Fake f = new Fake();
        f.put(0, 10, 0, 1, 64);
        f.tiles.put(new TileKey(1, 2, "minecraft:the_nether", 10, 1, 0), f.tiles.get(key(0, 10, 0)));
        for (int i = 11; i < 16; i++) f.put(0, i, 0, 1, 64);
        try (ClientHorizonView v = new ClientHorizonView(f, 3, 100, Runnable::run)) {
            v.update(0, 0, 0, 5000, k -> k.dimension().equals(DIM));
            var d = v.update(0, 0, 0, 5000, k -> k.dimension().equals(DIM));
            assertTrue(d.stream().noneMatch(x -> x.key().dimension().equals("minecraft:the_nether")));
            assertTrue(v.meshedKeys().size() <= 3, "cap of 3 meshes: " + v.meshedKeys().size());
            assertEquals(3, d.size());
            assertEquals(key(0, 10, 0), d.get(0).key(), "nearest first");
        }
    }
}
