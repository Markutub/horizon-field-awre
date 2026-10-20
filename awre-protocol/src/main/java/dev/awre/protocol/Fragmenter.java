package dev.awre.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/** Splits one TILE body into FRAGMENT slices that each fit one frame. n = ceil(L / (maxFrame - 14 - 18)). */
public final class Fragmenter {
    private Fragmenter() { }

    /** Payload bytes available per fragment frame. */
    public static int chunkSize(int maxFrame) {
        return Math.min(maxFrame, Awre.HARD_MAX_FRAME) - Frame.OVERHEAD - Awre.FRAGMENT_HEADER;
    }

    public static int fragmentCount(int bodyLen, int maxFrame) {
        int c = chunkSize(maxFrame);
        return (bodyLen + c - 1) / c;
    }

    /** @throws IllegalArgumentException if it would need more than {@link Awre#MAX_FRAGMENTS} slices or fewer than 2 */
    public static List<Fragment> split(byte[] tileBody, long msgId, int maxFrame) {
        int chunk = chunkSize(maxFrame);
        if (chunk < 64) throw new IllegalArgumentException("maxFrame too small to fragment");
        int n = fragmentCount(tileBody.length, maxFrame);
        if (n < 2) throw new IllegalArgumentException("fits one frame; do not fragment");
        if (n > Awre.MAX_FRAGMENTS) throw new IllegalArgumentException("needs " + n + " fragments > " + Awre.MAX_FRAGMENTS);
        CRC32 crc = new CRC32();
        crc.update(tileBody, 0, tileBody.length);
        List<Fragment> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int from = i * chunk, to = Math.min(tileBody.length, from + chunk);
            out.add(new Fragment(msgId, i, n, tileBody.length, crc.getValue(), chunk, Arrays.copyOfRange(tileBody, from, to)));
        }
        return out;
    }
}
