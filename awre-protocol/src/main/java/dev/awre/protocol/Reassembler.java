package dev.awre.protocol;

import dev.awre.protocol.ProtocolException.Reason;
import java.util.BitSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Bounded reassembly of fragmented tile bodies. Not thread-safe by itself (ClientSession synchronizes).
 * Memory bound: at most {@link Awre#MAX_ASSEMBLIES} buffers of at most the caller's maxTotal bytes, each validated
 * by {@link Fragment#decode} before allocation. Partial tiles expire after {@link Awre#ASSEMBLY_TIMEOUT_TICKS}.
 */
public final class Reassembler {
    private static final class Asm {
        final int count, total, chunk;
        final long crc;
        final byte[] buf;
        final BitSet seen = new BitSet();
        int got, age;
        Asm(Fragment f) { count = f.count(); total = f.totalLen(); chunk = f.chunkSize(); crc = f.wholeCrc(); buf = new byte[total]; }
        boolean matches(Fragment f) { return count == f.count() && total == f.totalLen() && chunk == f.chunkSize() && crc == f.wholeCrc(); }
    }

    private final Map<Long, Asm> active = new LinkedHashMap<>();
    private final Counters counters;

    public Reassembler(Counters counters) { this.counters = counters; }

    /** @return the complete body when this slice finishes a tile, else null. @throws ProtocolException on inconsistent slices or CRC failure */
    public byte[] accept(Fragment f) {
        Asm a = active.get(f.msgId());
        if (a == null) {
            while (active.size() >= Awre.MAX_ASSEMBLIES) {
                Iterator<Long> it = active.keySet().iterator();
                it.next(); it.remove();
                counters.inc("reasm.evicted");
            }
            a = new Asm(f);
            active.put(f.msgId(), a);
        } else if (!a.matches(f)) {
            active.remove(f.msgId());
            throw new ProtocolException(Reason.BAD_FIELD, "fragment header changed mid-tile");
        }
        if (a.seen.get(f.index())) { counters.inc("reasm.duplicate"); return null; }
        System.arraycopy(f.data(), 0, a.buf, f.index() * a.chunk, f.data().length);
        a.seen.set(f.index());
        a.got++;
        counters.inc("reasm.fragment");
        if (a.got < a.count) return null;
        active.remove(f.msgId());
        CRC32 crc = new CRC32();
        crc.update(a.buf, 0, a.buf.length);
        if (crc.getValue() != a.crc) throw new ProtocolException(Reason.CHECKSUM_MISMATCH, "reassembled crc");
        counters.inc("reasm.complete");
        return a.buf;
    }

    /** Call once per client tick; drops partial tiles that stopped making progress. */
    public void tick() {
        for (Iterator<Asm> it = active.values().iterator(); it.hasNext(); ) {
            if (++it.next().age > Awre.ASSEMBLY_TIMEOUT_TICKS) { it.remove(); counters.inc("reasm.timeout"); }
        }
    }

    public int activeCount() { return active.size(); }
}
