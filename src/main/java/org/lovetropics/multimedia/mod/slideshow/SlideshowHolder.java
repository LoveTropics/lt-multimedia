package org.lovetropics.multimedia.mod.slideshow;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.resources.Identifier;

public record SlideshowHolder(
        Identifier id,
        Slideshow value
) {
    public static final Codec<SlideshowHolder> CODEC = Identifier.CODEC.comapFlatMap(
            id -> {
                SlideshowHolder holder = SlideshowRegistry.REGISTRY.get(id);
                return holder != null ? DataResult.success(holder) : DataResult.error(() -> "No slideshow with id: " + id);
            },
            SlideshowHolder::id
    );
}
