package dev.horizonfield.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Append-only, CRC-protected log of {@link ColumnTile}s plus palette entries. Last record for a chunk wins on replay.
 * Crash recovery: a truncated or corrupt tail is detected by length/CRC and cut off; earlier records stay valid.
 * A header fingerprint (format + world identity + generator hint) invalidates the whole cache when it changes.
 * Compaction is NOT implemented yet (see docs/HANDOFF.md).
 *
 * File: magic "HFL1" | int formatVersion | UTF fingerprint | records...
 * Record: byte type | int length | payload | int crc32(type+payload)
 */
public final class TileLog implements AutoCloseable {
    public static final int FORMAT_VERSION = 1;
    private static final byte[] MAGIC = {'H', 'F', 'L', '1'};
    private static final byte T_PALETTE = 1, T_TILE = 2;

    public record LoadReport(int tiles, long discardedBytes, boolean freshStart, String reason) { }

    private final FileChannel channel;
    private final MaterialPalette palette;
    private int paletteWritten;

    private TileLog(FileChannel ch, MaterialPalette palette, int paletteWritten) {
        this.channel = ch;
        this.palette = palette;
        this.paletteWritten = paletteWritten;
    }

    /** Result holder for open(). */
    public record Opened(TileLog log, LoadReport report) { }

    public static Opened open(Path file, String fingerprint, MaterialPalette palette, Consumer<ColumnTile> sink) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
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
                            sink.accept(decodeTile(payload));
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
        } else {
            ch.truncate(goodEnd);
            ch.position(goodEnd);
        }
        int written = fresh ? 1 : palette.size();
        return new Opened(new TileLog(ch, palette, written), new LoadReport(tiles, discarded, fresh, reason));
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
