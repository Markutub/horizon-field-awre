package dev.awre.fabric;

import dev.awre.protocol.Awre;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Raw-bytes payload for channel awre:main. On the wire a plugin message is "channel id + raw bytes" (no length prefix),
 * so the codec reads whatever remains in the packet. The decode path is allocation-bounded: anything above
 * {@link Awre#HARD_MAX_FRAME} is skipped WITHOUT being copied (it must still be consumed or the connection would error),
 * and is reported through {@code skippedLength}.
 * Names are Mojang mappings for 1.21.11 (Identifier replaced ResourceLocation in 1.21.11).
 */
public record AwrePayload(byte[] data, int skippedLength) implements CustomPacketPayload {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(Awre.NAMESPACE, Awre.PATH);
    public static final CustomPacketPayload.Type<AwrePayload> TYPE = new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, AwrePayload> CODEC = new StreamCodec<>() {
        @Override
        public AwrePayload decode(RegistryFriendlyByteBuf buf) {
            int n = buf.readableBytes();
            if (n > Awre.HARD_MAX_FRAME) {
                buf.skipBytes(n);
                return new AwrePayload(new byte[0], n);
            }
            byte[] b = new byte[n];
            buf.readBytes(b);
            return new AwrePayload(b, 0);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, AwrePayload p) {
            buf.writeBytes(p.data());
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
