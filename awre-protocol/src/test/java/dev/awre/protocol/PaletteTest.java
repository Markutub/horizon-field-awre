package dev.awre.protocol;

import static org.junit.jupiter.api.Assertions.*;

import dev.awre.protocol.ProtocolException.Reason;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaletteTest {
    private static final String DIM = "minecraft:overworld";

    private static List<String> keys(int n) {
        List<String> k = new ArrayList<>();
        for (int i = 0; i < n; i++) k.add("minecraft:block_" + i);
        return k;
    }

    private static byte[] raw(int start, int count, String... entries) {
        ByteWriter w = new ByteWriter().i64(1).i64(2).string(DIM, 48).u16(start).u16(count);
        for (String e : entries) w.string(e, 96);
        return w.toByteArray();
    }

    @Test void roundTrip() {
        PaletteUpdate u = new PaletteUpdate(1, 2, DIM, 5, List.of("minecraft:stone", "minecraft:grass_block"));
        assertEquals(u, PaletteUpdate.decode(u.encode()));
    }

    @Test void keyCharacterRules() {
        assertTrue(PaletteUpdate.validKey("minecraft:oak_log"));
        assertTrue(PaletteUpdate.validKey("mod_x:sub/dir.name-1"));
        for (String bad : new String[]{"", "stone", "Minecraft:Stone", "minecraft:st one", "minecraft:<b>", "minecraft:st\u00e9", "minecraft:a\nb", "x".repeat(97) + ":y"})
            assertFalse(PaletteUpdate.validKey(bad), bad);
    }

    @Test void decodeRejectsBadBodies() {
        assertEquals(Reason.OUT_OF_RANGE, assertThrows(ProtocolException.class, () -> PaletteUpdate.decode(raw(0, 0))).reason());
        assertEquals(Reason.OUT_OF_RANGE, assertThrows(ProtocolException.class, () -> PaletteUpdate.decode(raw(0, 129))).reason());
        assertEquals(Reason.OUT_OF_RANGE, assertThrows(ProtocolException.class, () -> PaletteUpdate.decode(raw(32767, 2, "a:b", "a:c"))).reason());
        assertEquals(Reason.BAD_FIELD, assertThrows(ProtocolException.class, () -> PaletteUpdate.decode(raw(0, 1, "NoColon"))).reason());
        byte[] ok = raw(0, 1, "a:b");
        assertThrows(ProtocolException.class, () -> PaletteUpdate.decode(java.util.Arrays.copyOf(ok, ok.length + 1)));
        assertThrows(ProtocolException.class, () -> PaletteUpdate.decode(java.util.Arrays.copyOf(ok, ok.length - 1)));
    }

    @Test void clientBuildsThePaletteAndToleratesIdenticalResends() {
        Harness h = new Harness();
        h.handshake();
        assertEquals(Awre.CAPS_ALL, h.client.negotiatedCaps());
        for (byte[] f : h.server.sendPalette(1, 2, DIM, 0, keys(300))) h.toClient(f);        // 3 messages
        assertEquals(keys(300), h.client.palette(1, 2, DIM));
        for (byte[] f : h.server.sendPalette(1, 2, DIM, 250, keys(300).subList(250, 300))) h.toClient(f);   // identical overlap
        assertEquals(300, h.client.palette(1, 2, DIM).size());
        assertEquals(0, h.client.strikes());
        assertTrue(h.client.palette(9, 9, DIM).isEmpty());
        assertEquals(3, h.server.sendPalette(1, 2, DIM, 0, keys(300)).size());
    }

    @Test void gapsAndConflictsAreStrikes() {
        Harness h = new Harness();
        h.handshake();
        h.server.sendPalette(1, 2, DIM, 0, List.of("a:b")).forEach(h::toClient);
        h.server.sendPalette(1, 2, DIM, 5, List.of("a:gap")).forEach(h::toClient);           // gap: ids 1..4 missing
        assertEquals(1, h.client.strikes());
        h.server.sendPalette(1, 2, DIM, 0, List.of("a:other")).forEach(h::toClient);        // id 0 changed
        assertEquals(2, h.client.strikes());
        assertEquals(List.of("a:b"), h.client.palette(1, 2, DIM));
    }

    @Test void clientKeepsAtMostSixteenPalettes() {
        Harness h = new Harness();
        h.handshake();
        for (int w = 0; w < 40; w++) h.server.sendPalette(w, 0, DIM, 0, List.of("a:b")).forEach(h::toClient);
        int held = 0;
        for (int w = 0; w < 40; w++) if (!h.client.palette(w, 0, DIM).isEmpty()) held++;
        assertEquals(Awre.MAX_CLIENT_PALETTES, held);
    }

    @Test void noPaletteWithoutTheCapability() {
        Harness h = new Harness();
        h.server.onFrame(Frame.encode(MessageType.HELLO, 1, new Hello(1, 1, Awre.CAP_TILE_V1, 16384, "old").encode(), 16384));
        assertTrue(h.server.sendPalette(1, 2, DIM, 0, keys(3)).isEmpty());
    }
}
