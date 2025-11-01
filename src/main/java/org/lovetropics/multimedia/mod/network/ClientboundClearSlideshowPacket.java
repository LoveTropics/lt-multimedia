package org.lovetropics.multimedia.mod.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.lovetropics.multimedia.mod.MultimediaMod;

public record ClientboundClearSlideshowPacket() implements CustomPacketPayload {
    public static final StreamCodec<ByteBuf, ClientboundClearSlideshowPacket> STREAM_CODEC = StreamCodec.unit(new ClientboundClearSlideshowPacket());

    public static final Type<ClientboundClearSlideshowPacket> TYPE = new Type<>(MultimediaMod.location("clear_slideshow"));

    @Override
    public Type<ClientboundClearSlideshowPacket> type() {
        return TYPE;
    }
}
