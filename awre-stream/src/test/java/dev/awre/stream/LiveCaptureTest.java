package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.*;

import dev.awre.protocol.Awre;
import dev.awre.protocol.ClientSession;
import dev.awre.protocol.Counters;
import dev.awre.protocol.Limits;
import dev.awre.protocol.ServerSession;
import dev.awre.protocol.TileKey;
import dev.horizonfield.core.ChunkView;
import dev.horizonfield.core.MaterialPalette;
import dev.horizonfield.core.Metrics;
import dev.horizonfield.core.lod.LodKey;
import dev.horizonfield.core.lod.LodModel;
import dev.horizonfield.core.lod.LodPlanner;
import dev.horizonfield.core.terrain.LodTile;
import dev.horizonfield.core.terrain.LodTileCodec;
import dev.horizonfield.core.terrain.TerrainStore;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Chunks arrive on worker threads WHILE the stream runs; new block types appear mid-stream. Palette must always be ahead of the tiles. */
class LiveCaptureTest {
    private record Wire(ServerSession server, ClientSession client) {
        void toServer(byte[] f) { for (byte[] r : server.onFrame(f)) toClient(r); }
        void toClient(byte[] f) { for (byte[] r : client.onFrame(f)) toServer(r); }
    }

    private static final String DIM = "minecraft:overworld";
    private static final long MSB = 5, LSB = 6;

    private static ChunkView chunk(int cx, int cz, boolean gold) {
        return new ChunkView() {
            @Override public int chunkX() { return cx; }
            @Override public int chunkZ() { return cz; }
            @Override public int minY() { return -64; }
            @Override public int highestBlockY(int lx, int lz) { return SyntheticTerrain.heightAt(cx * 16 + lx, cz * 16 + lz, 77); }
            @Override public String blockKey(int lx, int y, int lz) {
                if (gold && (cx + cz) % 7 == 0) return "minecraft:gold_block";
                return y < 70 ? "minecraft:sand" : "minecraft:stone";
            }
        };
    }

    @Test void streamsWhileChunksAreStillArrivingAndPaletteStaysAhead() throws Exception {
        LodModel model = LodModel.defaults();
        Limits lim = Limits.defaults().withStreaming(16, 64, 500).withClientTileCache(8192);
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        ServerSession server = new ServerSession(lim, clock::get, new Counters(), 9);
        ClientSession client = new ClientSession(lim, new Counters(), "t", Awre.PROTOCOL_MIN, Awre.PROTOCOL_MAX);
        client.setPayloadValidator(TerrainPayloads.TERRAIN);
        TerrainStore store = new TerrainStore(model.maxLod(), TerrainStore.Reduce.MEAN, 20_000);
        MaterialPalette palette = new MaterialPalette();
        Wire wire = new Wire(server, client);
        wire.toServer(client.start());
        PlayerStreamer s = new PlayerStreamer(server, store, palette, new LodPlanner(model),
                new StreamConfig(500, 400_000, 65_536, 0.1, 4096, 16, 256, 3), MSB, LSB, DIM, clock::get);

        try (ChunkIngest ingest = new ChunkIngest(store, palette, 4096, 2, new Metrics(), t -> fail("ingest: " + t))) {
            int submitted = 0;
            for (int tick = 0; tick < 4000; tick++) {
                // ~40 new chunks per tick in an expanding square; gold appears only after the first 2000 chunks
                for (int i = 0; i < 40 && submitted < 64 * 64; i++, submitted++) {
                    int ring = (int) Math.sqrt(submitted), idx = submitted;
                    ingest.submit(chunk(idx % 64 - 32, idx / 64 - 32, submitted > 2000), 0);
                }
                clock.addAndGet(50_000_000L);
                for (byte[] f : s.tick(0, 0, 0)) wire.toClient(f);
                // invariant at every tick: every material id inside every client tile is already in the client's palette
                if (tick % 25 == 0) {
                    int pal = client.palette(MSB, LSB, DIM).size();
                    for (TileKey k : client.tileKeys()) {
                        LodTile t = LodTileCodec.decode(new LodKey(k.lod(), k.tileX(), k.tileZ()), client.tile(k).decoded());
                        for (int i = 0; i < 256; i++) assertTrue(t.materialAt(i % 16, i / 16) < pal, "tick " + tick + " tile " + k + " uses id outside palette size " + pal);
                    }
                }
                if (submitted >= 64 * 64 && tick % 50 == 0) Thread.sleep(5);
            }
        }
        // let the stream settle with everything ingested
        for (int i = 0; i < 1500; i++) { clock.addAndGet(50_000_000L); for (byte[] f : s.tick(0, 0, 0)) wire.toClient(f); }
        assertEquals(palette.snapshot(), client.palette(MSB, LSB, DIM));
        assertTrue(palette.snapshot().contains("minecraft:gold_block"));
        assertEquals(0, client.strikes());
        for (LodKey k : s.plan().leaves()) {
            if (store.version(k) == 0) continue;
            var got = client.tile(new TileKey(MSB, LSB, DIM, k.tx(), k.tz(), k.lod()));
            assertNotNull(got, "missing " + k);
            assertEquals(store.version(k), got.revision(), "stale " + k);
            assertTrue(LodTileCodec.decode(k, got.decoded()).sameContent(store.tile(k)), "content " + k);
        }
        System.out.println("live capture: " + s.stats() + " palette=" + palette.size());
    }
}
