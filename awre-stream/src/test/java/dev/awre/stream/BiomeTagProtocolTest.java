package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.awre.protocol.PaletteUpdate;
import dev.horizonfield.core.terrain.BiomeTint;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Biome-tagged palette keys must survive the REAL protocol validator and a PALETTE encode/decode round trip. */
class BiomeTagProtocolTest {
    @Test void taggedKeysAreAcceptedByThePaletteProtocol() {
        List<String> keys = List.of(
                BiomeTint.tag("minecraft:grass_block", "minecraft:plains"),
                BiomeTint.tag("minecraft:water", "terralith:alpine_highlands"),
                BiomeTint.tag("minecraft:oak_leaves", "minecraft:dark_forest"));
        for (String k : keys) assertTrue(PaletteUpdate.validKey(k), k);
        PaletteUpdate u = new PaletteUpdate(1, 2, "minecraft:overworld", 5, keys);
        PaletteUpdate back = PaletteUpdate.decode(u.encode());
        assertEquals(keys, back.keys());
    }

    @Test void aTagThatWouldBeRejectedFallsBackToThePlainKey() {
        String k = BiomeTint.tag("minecraft:grass_block", "Mod:Weird Biome");
        assertEquals("minecraft:grass_block", k);
        assertTrue(PaletteUpdate.validKey(k));
    }
}
