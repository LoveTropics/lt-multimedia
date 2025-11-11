package org.lovetropics.multimedia.mod.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.lovetropics.multimedia.mod.entity.ScreenEntity;

public record SlideshowNetworkId(int id) {
    public static final SlideshowNetworkId FULL_SCREEN = new SlideshowNetworkId(-1);

    public static final StreamCodec<ByteBuf, SlideshowNetworkId> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(
            id -> new SlideshowNetworkId(id - 1),
            id -> id.id + 1
    );

    public static SlideshowNetworkId of(final ScreenEntity screen) {
        return new SlideshowNetworkId(screen.getId());
    }

    public boolean isFullScreen() {
        return equals(FULL_SCREEN);
    }
}
