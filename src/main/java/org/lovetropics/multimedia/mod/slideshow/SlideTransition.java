package org.lovetropics.multimedia.mod.slideshow;

import com.mojang.serialization.Codec;

import java.time.Duration;

public record SlideTransition(
        Duration duration
) {
    public static final SlideTransition NONE = new SlideTransition(Duration.ZERO);

    public static final Codec<SlideTransition> CODEC = Slide.SECONDS_CODEC.xmap(SlideTransition::new, SlideTransition::duration);
}
