package dev.awre.stream;

import dev.horizonfield.core.terrain.TerrainMesher;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Pure-Java conversion of a CPU {@link TerrainMesher.Mesh} to the bytes a GPU vertex/index buffer holds. No Minecraft classes.
 * Vertex layout (16 bytes, the same as Minecraft's POSITION_COLOR): float x, float y, float z, unsigned byte r, g, b, a.
 * Positions are copied as they are (tile-local for {@link TerrainMesher#meshLocal}); the camera offset is applied at draw time.
 * Because the vanilla position_color shader has no lighting, a fixed directional shade is baked into the colour so cliffs and tops
 * are distinguishable. Indices are 32-bit.
 */
public final class VertexPacker {
    public static final int VERTEX_BYTES = 16;
    public static final int INDEX_BYTES = 4;

    private VertexPacker() { }

    /** Brightness multiplier per {@link TerrainMesher.Face} ordinal: UP, NORTH, SOUTH, WEST, EAST, SKIRT. */
    private static final float[] SHADE = {1.0f, 0.80f, 0.80f, 0.60f, 0.60f, 0.70f};

    public static int vertexBytes(TerrainMesher.Mesh m) { return m.vertexCount() * VERTEX_BYTES; }
    public static int indexBytes(TerrainMesher.Mesh m) { return m.indices.length * INDEX_BYTES; }

    /** Direct, native-order buffer, flipped (position 0, limit = bytes), ready for a GPU upload. */
    public static ByteBuffer packVertices(TerrainMesher.Mesh m) {
        ByteBuffer b = ByteBuffer.allocateDirect(vertexBytes(m)).order(ByteOrder.nativeOrder());
        for (int v = 0; v < m.vertexCount(); v++) {
            b.putFloat(m.positions[v * 3]).putFloat(m.positions[v * 3 + 1]).putFloat(m.positions[v * 3 + 2]);
            int argb = m.colors[v];
            float shade = SHADE[Math.min(SHADE.length - 1, Math.max(0, m.faces[v]))];
            b.put(shaded(argb >> 16, shade)).put(shaded(argb >> 8, shade)).put(shaded(argb, shade)).put((byte) (argb >>> 24));
        }
        return b.flip();
    }

    public static ByteBuffer packIndices(TerrainMesher.Mesh m) {
        ByteBuffer b = ByteBuffer.allocateDirect(indexBytes(m)).order(ByteOrder.nativeOrder());
        for (int i : m.indices) b.putInt(i);
        return b.flip();
    }

    private static byte shaded(int channel, float shade) { return (byte) Math.min(255, Math.round((channel & 0xFF) * shade)); }
}
