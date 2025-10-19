package org.lovetropics.multimedia.mod.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.lovetropics.multimedia.mod.MultimediaMod;

public record ClientboundClearSlideshowPacket() implements CustomPacketPayload {
    public static final StreamCodec<ByteBuf, ClientboundClearSlideshowPacket> STREAM_CODEC = StreamCodec.unit(new ClientboundClearSlideshowPacket());

    public static final Type<ClientboundClearSlideshowPacket> TYPE = new Type<>(MultimediaMod.location("clear_slideshow"));

    public static void handle(final ClientboundClearSlideshowPacket packet, final IPayloadContext context) {
        MultimediaMod.slideshowManager().clear();
    }

    @Override
    public Type<ClientboundClearSlideshowPacket> type() {
        return TYPE;
    }
}
