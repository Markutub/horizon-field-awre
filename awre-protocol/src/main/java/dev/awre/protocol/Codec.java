package dev.awre.protocol;

import dev.awre.protocol.ProtocolException.Reason;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Compression helpers. Inflation is hard-bounded: output memory never exceeds maxDecoded + 1 bytes. */
public final class Codec {
    private Codec() { }

    public static byte[] deflate(byte[] raw) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
        try {
            d.setInput(raw); d.finish();
            byte[] buf = new byte[Math.max(64, raw.length + raw.length / 8 + 64)];
            int n = 0;
            while (!d.finished()) {
                if (n == buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                n += d.deflate(buf, n, buf.length - n);
            }
            return java.util.Arrays.copyOf(buf, n);
        } finally { d.end(); }
    }

    /** @throws ProtocolException INFLATE_LIMIT if the output would exceed maxDecoded, TRUNCATED/BAD_FIELD for broken streams */
    public static byte[] inflateBounded(byte[] comp, int maxDecoded) {
        Inflater inf = new Inflater(true);
        try {
            inf.setInput(comp);
            byte[] out = new byte[Math.min(maxDecoded + 1, 4096)];
            int n = 0;
            while (!inf.finished()) {
                if (n == out.length) {
                    if (out.length > maxDecoded) throw new ProtocolException(Reason.INFLATE_LIMIT, "> " + maxDecoded);
                    out = java.util.Arrays.copyOf(out, Math.min(maxDecoded + 1, out.length * 2));
                }
                int got = inf.inflate(out, n, out.length - n);
                if (got == 0 && (inf.needsInput() || inf.needsDictionary())) throw new ProtocolException(Reason.TRUNCATED, "deflate stream");
                n += got;
                if (n > maxDecoded) throw new ProtocolException(Reason.INFLATE_LIMIT, "> " + maxDecoded);
            }
            if (inf.getRemaining() != 0) throw new ProtocolException(Reason.TRAILING_BYTES, "after deflate stream");
            return java.util.Arrays.copyOf(out, n);
        } catch (DataFormatException e) {
            throw new ProtocolException(Reason.BAD_FIELD, "deflate");
        } finally { inf.end(); }
    }
}
