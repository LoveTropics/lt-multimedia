package org.lovetropics.multimedia.mod.slideshow;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.lovetropics.multimedia.mod.entity.ScreenEntity;

public record SlideshowInstanceId(int id) {
    public static final SlideshowInstanceId FULL_SCREEN = new SlideshowInstanceId(-1);

    public static final StreamCodec<ByteBuf, SlideshowInstanceId> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(
            id -> new SlideshowInstanceId(id - 1),
            id -> id.id + 1
    );

    public static SlideshowInstanceId of(final ScreenEntity screen) {
        return new SlideshowInstanceId(screen.getId());
    }

    public boolean isFullScreen() {
        return equals(FULL_SCREEN);
    }
}
