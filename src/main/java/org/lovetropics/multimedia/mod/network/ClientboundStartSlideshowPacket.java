package org.lovetropics.multimedia.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.lovetropics.multimedia.mod.MultimediaMod;
import org.lovetropics.multimedia.mod.slideshow.Slideshow;

public record ClientboundStartSlideshowPacket(
        Slideshow sequence
) implements CustomPacketPayload {
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundStartSlideshowPacket> STREAM_CODEC = StreamCodec.composite(
            Slideshow.STREAM_CODEC, ClientboundStartSlideshowPacket::sequence,
            ClientboundStartSlideshowPacket::new
    );

    public static final Type<ClientboundStartSlideshowPacket> TYPE = new Type<>(MultimediaMod.location("start_slideshow"));

    @Override
    public Type<ClientboundStartSlideshowPacket> type() {
        return TYPE;
    }
}
