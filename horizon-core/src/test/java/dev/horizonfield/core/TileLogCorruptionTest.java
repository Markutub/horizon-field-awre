package dev.horizonfield.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Adversarial tests for the persistent terrain cache. The contract: whatever bytes are on disk, {@link TileLog#open} never throws
 * (except for programmer errors), replays only records that pass their CRC and decode, cuts everything after the first bad record, and
 * leaves a log that accepts new appends and replays them next time. A corrupted or crafted cache must cost at most the data it damaged.
 */
class TileLogCorruptionTest {
    private static final String FP = "fp-corrupt";

    /** A valid log with 40 chunk records over 24 chunks (some chunks recorded twice). Returns the file bytes. */
    private static byte[] validLog(Path f, Map<Long, ColumnTile> lastByChunk) throws Exception {
        MaterialPalette pal = new MaterialPalette();
        int stone = pal.idOf("minecraft:stone"), sand = pal.idOf("minecraft:sand"), grass = pal.idOf("minecraft:grass_block");
        TileLog log = TileLog.open(f, FP, pal, t -> { }).log();
        for (int i = 0; i < 40; i++) {
            ColumnTile t = TestUtil.flat(i % 24, i / 24, 60 + (i * 7) % 23, i % 3 == 0 ? stone : i % 3 == 1 ? sand : grass);
            log.append(t);
            lastByChunk.put(HeightPyramid.key(t.chunkX(), t.chunkZ()), t);
        }
        log.close();
        return Files.readAllBytes(f);
    }

    private static List<ColumnTile> replay(Path f) throws Exception {
        List<ColumnTile> got = new ArrayList<>();
        TileLog.Opened o = TileLog.open(f, FP, new MaterialPalette(), got::add);
        o.log().close();
        return got;
    }

    /**
     * A record whose length field is near Integer.MAX_VALUE: with an int position, pos + 5 + len + 4 wraps negative, the bounds check
     * passes, and CRC32.update throws IndexOutOfBoundsException out of open(). Regression test for that overflow.
     */
    @Test void aLengthFieldNearIntegerMaxIsATornTailNotAnException() throws Exception {
        Path f = Files.createTempDirectory("hf-corrupt").resolve("w.hfl");
        Map<Long, ColumnTile> last = new HashMap<>();
        byte[] good = validLog(f, last);
        for (int len : new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE - 8, Integer.MAX_VALUE - 13, Integer.MAX_VALUE - 60, 0x7FFFFF00}) {
            byte[] bad = java.util.Arrays.copyOf(good, good.length + 5 + 12);
            bad[good.length] = 2;                                                    // tile record type
            bad[good.length + 1] = (byte) (len >>> 24); bad[good.length + 2] = (byte) (len >>> 16);
            bad[good.length + 3] = (byte) (len >>> 8); bad[good.length + 4] = (byte) len;
            Files.write(f, bad);
            List<ColumnTile> got = replay(f);                                        // must not throw
            assertEquals(40, got.size(), "every valid record before the damaged one is replayed, len " + len);
            assertEquals(good.length, Files.size(f), "the damaged tail is cut, len " + len);
        }
    }

    /** A record whose CRC is correct but whose content cannot be decoded must cut the log THERE, not discard the valid records before it. */
    @Test void aCrcValidButUndecodableRecordKeepsEverythingBeforeIt() throws Exception {
        Path f = Files.createTempDirectory("hf-corrupt").resolve("w.hfl");
        Map<Long, ColumnTile> last = new HashMap<>();
        byte[] good = validLog(f, last);
        for (int type : new int[]{2, 1}) {                                             // tile record, palette record
            byte[] payload = {9, 9, 9, 9, 9, 9, 9};                                    // too short / nonsense for either decoder
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            bo.write(good, 0, good.length);
            java.io.ByteArrayOutputStream rec = new java.io.ByteArrayOutputStream();
            rec.write(type); rec.write(0); rec.write(0); rec.write(0); rec.write(payload.length); rec.write(payload);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(rec.toByteArray());
            int c = (int) crc.getValue();
            rec.write(c >>> 24); rec.write(c >>> 16); rec.write(c >>> 8); rec.write(c);
            bo.write(rec.toByteArray());
            Files.write(f, bo.toByteArray());
            assertEquals(40, replay(f).size(), "the 40 good records survive, type " + type);
            assertEquals(good.length, Files.size(f), "the undecodable record is cut, type " + type);
        }
    }

    @Test void everyTruncationPointAndRandomMutationsNeverThrowAndNeverInventData() throws Exception {
        Path f = Files.createTempDirectory("hf-corrupt").resolve("w.hfl");
        Map<Long, ColumnTile> last = new HashMap<>();
        byte[] good = validLog(f, last);
        // every record the original log holds, to check that anything replayed is genuine
        List<ColumnTile> original = replay(f);
        assertEquals(40, original.size());

        Random r = new Random(20261023);
        int cases = 0, replayedSomething = 0;
        List<byte[]> mutants = new ArrayList<>();
        for (int len = 0; len < good.length; len += 7) mutants.add(java.util.Arrays.copyOf(good, len));      // truncations
        for (int i = 0; i < 1500; i++) {
            byte[] b = good.clone();
            switch (r.nextInt(5)) {
                case 0 -> { for (int k = 0, n = 1 + r.nextInt(6); k < n; k++) b[r.nextInt(b.length)] ^= (byte) (1 << r.nextInt(8)); }
                case 1 -> { int off = r.nextInt(b.length), n = Math.min(b.length - off, 1 + r.nextInt(64)); for (int k = 0; k < n; k++) b[off + k] = (byte) (r.nextBoolean() ? 0xFF : 0x00); }
                case 2 -> { int off = r.nextInt(b.length), n = Math.min(b.length - off, 1 + r.nextInt(64)); byte[] noise = new byte[n]; r.nextBytes(noise); System.arraycopy(noise, 0, b, off, n); }
                case 3 -> { b = java.util.Arrays.copyOf(b, b.length + 1 + r.nextInt(64)); for (int k = good.length; k < b.length; k++) b[k] = (byte) r.nextInt(256); }   // random bytes appended
                default -> { int p = r.nextInt(b.length - 4); b[p] = (byte) (r.nextBoolean() ? 0x7F : 0xFF); b[p + 1] = (byte) 0xFF; b[p + 2] = (byte) 0xFF; b[p + 3] = (byte) (0xF0 + r.nextInt(16)); }   // forge a huge length-like field anywhere
            }
            mutants.add(b);
        }
        for (byte[] m : mutants) {
            Files.write(f, m);
            List<ColumnTile> got;
            try { got = replay(f); }
            catch (Throwable t) { fail("open() threw " + t + " for a mutated cache of " + m.length + " bytes (case " + cases + ")"); return; }
            cases++;
            if (!got.isEmpty()) replayedSomething++;
            for (ColumnTile t : got) {
                boolean genuine = false;
                for (ColumnTile o : original) if (o.sameContent(t)) { genuine = true; break; }
                assertTrue(genuine, "a replayed tile that never existed in the original log (case " + cases + ")");
            }
            // the log must still be usable: append one record and see it replayed next time
            MaterialPalette p = new MaterialPalette();
            TileLog.Opened o = TileLog.open(f, FP, p, t -> { });
            o.log().append(TestUtil.flat(900, 900, 99, 0));
            o.log().close();
            List<ColumnTile> after = replay(f);
            assertTrue(after.stream().anyMatch(t -> t.chunkX() == 900 && t.topAt(0, 0) == 99), "append after recovery must survive a reopen (case " + cases + ")");
        }
        assertTrue(cases > 1500);
        assertTrue(replayedSomething > cases / 2, "most mutations only damage a tail or one record, so a prefix survives");
    }
}
