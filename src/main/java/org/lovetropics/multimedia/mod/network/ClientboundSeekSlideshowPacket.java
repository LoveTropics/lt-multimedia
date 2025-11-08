package org.lovetropics.multimedia.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.lovetropics.multimedia.mod.MultimediaMod;
import org.lovetropics.multimedia.mod.slideshow.SlideshowInstanceId;

public record ClientboundSeekSlideshowPacket(
        SlideshowInstanceId id,
        double time,
        boolean paused
) implements CustomPacketPayload {
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundSeekSlideshowPacket> STREAM_CODEC = StreamCodec.composite(
            SlideshowInstanceId.STREAM_CODEC, ClientboundSeekSlideshowPacket::id,
            ByteBufCodecs.DOUBLE, ClientboundSeekSlideshowPacket::time,
            ByteBufCodecs.BOOL, ClientboundSeekSlideshowPacket::paused,
            ClientboundSeekSlideshowPacket::new
    );

    public static final Type<ClientboundSeekSlideshowPacket> TYPE = new Type<>(MultimediaMod.location("seek_slideshow"));

    @Override
    public Type<ClientboundSeekSlideshowPacket> type() {
        return TYPE;
    }
}
