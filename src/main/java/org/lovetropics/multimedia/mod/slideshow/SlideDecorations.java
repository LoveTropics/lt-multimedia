package org.lovetropics.multimedia.mod.slideshow;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

public record SlideDecorations(
        Component header,
        Component body
) {
    public static final MapCodec<SlideDecorations> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            ComponentSerialization.CODEC.optionalFieldOf("header", CommonComponents.EMPTY).forGetter(SlideDecorations::header),
            ComponentSerialization.CODEC.optionalFieldOf("body", CommonComponents.EMPTY).forGetter(SlideDecorations::body)
    ).apply(i, SlideDecorations::new));
}
