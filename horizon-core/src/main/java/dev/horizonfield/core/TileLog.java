package dev.horizonfield.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Append-only, CRC-protected log of {@link ColumnTile}s plus palette entries. Last record for a chunk wins on replay.
 * Crash recovery: a truncated or corrupt tail is detected by length/CRC and cut off; earlier records stay valid.
 * A header fingerprint (format + world identity + generator hint) invalidates the whole cache when it changes.
 * Compaction ({@link #compact()}) rewrites the file with the palette records plus the LAST tile of every chunk, via a temp file
 * ("<file>.compact", fsynced) that atomically replaces the log; a crash at any point leaves either the old or the new file
 * valid, and a leftover temp file is deleted on the next open. The format is unchanged (still version 1).
 *
 * File: magic "HFL1" | int formatVersion | UTF fingerprint | records...
 * Record: byte type | int length | payload | int crc32(type+payload)
 */
public final class TileLog implements AutoCloseable {
    public static final int FORMAT_VERSION = 1;
    private static final byte[] MAGIC = {'H', 'F', 'L', '1'};
    private static final byte T_PALETTE = 1, T_TILE = 2;

    public record LoadReport(int tiles, long discardedBytes, boolean freshStart, String reason) { }

    /** Outcome of {@link #compact()}: tile records and file bytes before/after. */
    public record CompactReport(int recordsBefore, int recordsAfter, long bytesBefore, long bytesAfter) {
        public long bytesSaved() { return bytesBefore - bytesAfter; }
    }

    private FileChannel channel;                 // replaced by compact(); guarded by this
    private final Path file;
    private final String fingerprint;
    private final MaterialPalette palette;
    private int paletteWritten;
    private int tileRecords;                     // tile records currently in the file (live + superseded)
    private final HashSet<Long> liveKeys;        // chunks that have at least one record; memory: one boxed long per chunk

    private TileLog(Path file, String fingerprint, FileChannel ch, MaterialPalette palette, int paletteWritten, int tileRecords, HashSet<Long> liveKeys) {
        this.file = file;
        this.fingerprint = fingerprint;
        this.channel = ch;
        this.palette = palette;
        this.paletteWritten = paletteWritten;
        this.tileRecords = tileRecords;
        this.liveKeys = liveKeys;
    }

    private static Path tmpPath(Path file) { return file.resolveSibling(file.getFileName() + ".compact"); }

    /** Result holder for open(). */
    public record Opened(TileLog log, LoadReport report) { }

    public static Opened open(Path file, String fingerprint, MaterialPalette palette, Consumer<ColumnTile> sink) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.deleteIfExists(tmpPath(file));     // leftover of a compaction that crashed before the atomic swap: the log itself is intact
        HashSet<Long> keys = new HashSet<>();
        boolean fresh = !Files.exists(file);
        String reason = fresh ? "no cache file" : null;
        byte[] data = fresh ? new byte[0] : Files.readAllBytes(file);
        int tiles = 0;
        long goodEnd = 0;
        if (!fresh && palette.size() != 1) {
            // Replaying restores palette ids from the file; a palette shared with another world would mismatch or corrupt ids.
            throw new IllegalArgumentException("replaying an existing log requires a brand-new MaterialPalette (one palette per log)");
        }
        if (!fresh) {
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
                byte[] m = new byte[4];
                in.readFully(m);
                int ver = in.readInt();
                String fp = in.readUTF();
                if (!java.util.Arrays.equals(m, MAGIC) || ver != FORMAT_VERSION || !fp.equals(fingerprint)) {
                    fresh = true;
                    reason = "header/fingerprint mismatch";
                } else {
                    goodEnd = 4 + 4 + 2 + fp.getBytes(StandardCharsets.UTF_8).length;
                    int pos = (int) goodEnd;
                    while (pos + 5 <= data.length) {
                        byte type = data[pos];
                        int len = ((data[pos + 1] & 0xFF) << 24) | ((data[pos + 2] & 0xFF) << 16) | ((data[pos + 3] & 0xFF) << 8) | (data[pos + 4] & 0xFF);
                        if (len < 0 || pos + 5 + len + 4 > data.length) break;
                        CRC32 crc = new CRC32();
                        crc.update(data, pos, 5 + len);
                        int stored = ((data[pos + 5 + len] & 0xFF) << 24) | ((data[pos + 6 + len] & 0xFF) << 16)
                                | ((data[pos + 7 + len] & 0xFF) << 8) | (data[pos + 8 + len] & 0xFF);
                        if ((int) crc.getValue() != stored) break;
                        byte[] payload = java.util.Arrays.copyOfRange(data, pos + 5, pos + 5 + len);
                        if (type == T_PALETTE) {
                            DataInputStream p = new DataInputStream(new ByteArrayInputStream(payload));
                            palette.restore(p.readShort(), p.readUTF());
                        } else if (type == T_TILE) {
                            ColumnTile ct = decodeTile(payload);
                            keys.add(HeightPyramid.key(ct.chunkX(), ct.chunkZ()));
                            sink.accept(ct);
                            tiles++;
                        }
                        pos += 5 + len + 4;
                        goodEnd = pos;
                    }
                }
            } catch (IOException eof) {
                fresh = true;
                reason = "unreadable header";
            }
        }
        long discarded = fresh ? data.length : data.length - goodEnd;
        FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
        if (fresh) {
            ch.truncate(0);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (DataOutputStream d = new DataOutputStream(bo)) {
                d.write(MAGIC);
                d.writeInt(FORMAT_VERSION);
                d.writeUTF(fingerprint);
            }
            ch.position(0);
            ch.write(java.nio.ByteBuffer.wrap(bo.toByteArray()));
            ch.force(true);
            tiles = 0;
            keys.clear();
        } else {
            ch.truncate(goodEnd);
            ch.position(goodEnd);
        }
        int written = fresh ? 1 : palette.size();
        return new Opened(new TileLog(file, fingerprint, ch, palette, written, tiles, keys), new LoadReport(tiles, discarded, fresh, reason));
    }

    public synchronized void append(ColumnTile t) throws IOException {
        while (paletteWritten < palette.size()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (DataOutputStream d = new DataOutputStream(bo)) {
                d.writeShort(paletteWritten);
                d.writeUTF(palette.keyOf(paletteWritten));
            }
            writeRecord(T_PALETTE, bo.toByteArray());
            paletteWritten++;
        }
        writeRecord(T_TILE, encodeTile(t));
        tileRecords++;
        liveKeys.add(HeightPyramid.key(t.chunkX(), t.chunkZ()));
    }

    /** Tile records in the file, including superseded ones. */
    public synchronized int tileRecords() { return tileRecords; }

    /** Distinct chunks in the file (what a replay keeps). */
    public synchronized int liveTiles() { return liveKeys.size(); }

    /** Fraction of tile records that a compaction would drop (0 = none, approaches 1 when most are superseded). */
    public synchronized double garbageRatio() { return tileRecords == 0 ? 0.0 : 1.0 - (double) liveKeys.size() / tileRecords; }

    public synchronized long sizeBytes() throws IOException { return channel.size(); }

    /**
     * Rewrites the log keeping every palette record and only the last valid tile record per chunk, in original order. Appends
     * and flushes wait while this runs (it holds the log's monitor). On any failure the original file stays in use and valid.
     */
    public synchronized CompactReport compact() throws IOException {
        channel.force(false);
        final long before = channel.size();
        final int headerLen = 4 + 4 + 2 + fingerprint.getBytes(StandardCharsets.UTF_8).length;
        List<long[]> palettes = new ArrayList<>();                 // {offset, length}
        Map<Long, long[]> latest = new HashMap<>();                // chunk key -> {offset, length} of its last valid record
        int records = 0;
        long pos = headerLen;
        ByteBuffer head = ByteBuffer.allocate(5);
        while (pos + 5 <= before) {
            head.clear();
            readFully(head, pos);
            byte type = head.get(0);
            int len = head.getInt(1);
            if (len < 0 || pos + 5 + len + 4 > before) break;      // torn tail: everything before it is still good
            byte[] rec = new byte[5 + len + 4];
            readFully(ByteBuffer.wrap(rec), pos);
            CRC32 crc = new CRC32();
            crc.update(rec, 0, 5 + len);
            if ((int) crc.getValue() != ByteBuffer.wrap(rec, 5 + len, 4).getInt()) break;
            if (type == T_PALETTE) {
                palettes.add(new long[]{pos, rec.length});
            } else if (type == T_TILE) {
                if (len < 8) break;
                ByteBuffer p = ByteBuffer.wrap(rec, 5, 8);
                latest.put(HeightPyramid.key(p.getInt(), p.getInt()), new long[]{pos, rec.length});
                records++;
            }
            pos += rec.length;
        }
        List<long[]> live = new ArrayList<>(latest.values());
        live.sort((a, b) -> Long.compare(a[0], b[0]));

        Path tmp = tmpPath(file);
        try (FileChannel out = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (DataOutputStream d = new DataOutputStream(bo)) {
                d.write(MAGIC);
                d.writeInt(FORMAT_VERSION);
                d.writeUTF(fingerprint);
            }
            writeFully(out, ByteBuffer.wrap(bo.toByteArray()));
            for (long[] r : palettes) copyRecord(out, r);
            for (long[] r : live) copyRecord(out, r);
            out.force(true);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        long after = Files.size(tmp);

        channel.close();                                           // Windows cannot replace an open file
        try {
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;                                               // the finally below reopens the untouched original
        } finally {
            channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.READ);
            channel.position(channel.size());
        }
        tileRecords = live.size();
        liveKeys.clear();
        liveKeys.addAll(latest.keySet());
        return new CompactReport(records, live.size(), before, after);
    }

    private void copyRecord(FileChannel out, long[] r) throws IOException {
        ByteBuffer b = ByteBuffer.allocate((int) r[1]);
        readFully(b, r[0]);
        b.flip();
        writeFully(out, b);
    }

    private void readFully(ByteBuffer b, long position) throws IOException {
        long p = position;
        while (b.hasRemaining()) {
            int n = channel.read(b, p);
            if (n < 0) throw new java.io.EOFException("log shorter than expected at " + p);
            p += n;
        }
    }

    private static void writeFully(FileChannel ch, ByteBuffer b) throws IOException {
        while (b.hasRemaining()) ch.write(b);
    }

    public synchronized void flush() throws IOException { channel.force(false); }

    @Override
    public synchronized void close() throws IOException {
        if (channel.isOpen()) { channel.force(false); channel.close(); }
    }

    private void writeRecord(byte type, byte[] payload) throws IOException {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(5 + payload.length + 4);
        b.put(type).putInt(payload.length).put(payload);
        CRC32 crc = new CRC32();
        crc.update(b.array(), 0, 5 + payload.length);
        b.putInt((int) crc.getValue());
        b.flip();
        while (b.hasRemaining()) channel.write(b);
    }

    static byte[] encodeTile(ColumnTile t) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (DataOutputStream d = new DataOutputStream(bo)) {
            d.writeInt(t.chunkX());
            d.writeInt(t.chunkZ());
            d.writeByte(t.confidence().ordinal());
            d.writeLong(t.observedAtMillis());
            Deflater def = new Deflater(Deflater.BEST_SPEED);
            try (DataOutputStream z = new DataOutputStream(new DeflaterOutputStream(d, def))) {
                for (short s : t.topArray()) z.writeShort(s);
                for (short s : t.materialArray()) z.writeShort(s);
            } finally {
                def.end();
            }
        }
        return bo.toByteArray();
    }

    static ColumnTile decodeTile(byte[] payload) throws IOException {
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(payload));
        int cx = d.readInt(), cz = d.readInt();
        Confidence conf = Confidence.values()[d.readByte()];
        long at = d.readLong();
        short[] top = new short[ColumnTile.AREA], mat = new short[ColumnTile.AREA];
        try (DataInputStream z = new DataInputStream(new InflaterInputStream(d))) {
            for (int i = 0; i < ColumnTile.AREA; i++) top[i] = z.readShort();
            for (int i = 0; i < ColumnTile.AREA; i++) mat[i] = z.readShort();
        }
        return new ColumnTile(cx, cz, top, mat, conf, at);
    }
}
