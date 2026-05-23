package org.lovetropics.multimedia.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.lovetropics.multimedia.mod.MultimediaMod;
import org.lovetropics.multimedia.mod.slideshow.Slideshow;

public record ClientboundStartSlideshowPacket(
        SlideshowNetworkId id,
        Slideshow slideshow,
        double time,
        boolean paused
) implements CustomPacketPayload {
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundStartSlideshowPacket> STREAM_CODEC = StreamCodec.composite(
            SlideshowNetworkId.STREAM_CODEC, ClientboundStartSlideshowPacket::id,
            Slideshow.STREAM_CODEC, ClientboundStartSlideshowPacket::slideshow,
            ByteBufCodecs.DOUBLE, ClientboundStartSlideshowPacket::time,
            ByteBufCodecs.BOOL, ClientboundStartSlideshowPacket::paused,
            ClientboundStartSlideshowPacket::new
    );

    public static final Type<ClientboundStartSlideshowPacket> TYPE = new Type<>(MultimediaMod.id("start_slideshow"));

    @Override
    public Type<ClientboundStartSlideshowPacket> type() {
        return TYPE;
    }
}
