package dev.horizonfield.core;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * READ-ONLY reader for TileLog files (format v1). Unlike {@link TileLog#open}, this never writes, truncates or
 * recreates the file, and accepts any world fingerprint. Use it for offline analysis of a cache copied from a server.
 * Records are delivered in file order; a later record for the same chunk supersedes an earlier one (caller keeps the latest).
 * Reading stops at the first record that is truncated or fails its CRC (same rule as TileLog), reported via discardedBytes().
 */
public final class TileLogReader {
    private TileLogReader() { }

    public record Result(String fingerprint, int formatVersion, int records, long goodBytes, long totalBytes, MaterialPalette palette) {
        public long discardedBytes() { return totalBytes - goodBytes; }
    }

    public static Result read(Path file, Consumer<ColumnTile> sink) throws IOException {
        byte[] data = Files.readAllBytes(file);
        MaterialPalette palette = new MaterialPalette();
        String fp;
        int ver;
        long goodEnd;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            byte[] m = new byte[4];
            in.readFully(m);
            if (!Arrays.equals(m, new byte[]{'H', 'F', 'L', '1'})) throw new IOException("not a HorizonField tile log (bad magic)");
            ver = in.readInt();
            if (ver != TileLog.FORMAT_VERSION) throw new IOException("unsupported format version " + ver);
            fp = in.readUTF();
            goodEnd = 4 + 4 + 2 + fp.getBytes(StandardCharsets.UTF_8).length;
        }
        int pos = (int) goodEnd;
        int records = 0;
        while (pos + 5 <= data.length) {
            byte type = data[pos];
            int len = ((data[pos + 1] & 0xFF) << 24) | ((data[pos + 2] & 0xFF) << 16) | ((data[pos + 3] & 0xFF) << 8) | (data[pos + 4] & 0xFF);
            if (len < 0 || pos + 5 + len + 4 > data.length) break;
            CRC32 crc = new CRC32();
            crc.update(data, pos, 5 + len);
            int stored = ((data[pos + 5 + len] & 0xFF) << 24) | ((data[pos + 6 + len] & 0xFF) << 16)
                    | ((data[pos + 7 + len] & 0xFF) << 8) | (data[pos + 8 + len] & 0xFF);
            if ((int) crc.getValue() != stored) break;
            byte[] payload = Arrays.copyOfRange(data, pos + 5, pos + 5 + len);
            try {
                if (type == 1) {
                    DataInputStream p = new DataInputStream(new ByteArrayInputStream(payload));
                    palette.restore(p.readShort(), p.readUTF());
                } else if (type == 2) {
                    sink.accept(TileLog.decodeTile(payload));
                    records++;
                }
            } catch (IllegalStateException e) {
                throw new IOException("inconsistent palette record: " + e.getMessage(), e);
            }
            pos += 5 + len + 4;
            goodEnd = pos;
        }
        return new Result(fp, ver, records, goodEnd, data.length, palette);
    }
}
