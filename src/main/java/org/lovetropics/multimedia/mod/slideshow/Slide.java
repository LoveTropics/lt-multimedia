package org.lovetropics.multimedia.mod.slideshow;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.time.Duration;
import java.util.Optional;

public record Slide(
        SlideContent content,
        Optional<SlideTransition> transitionIn,
        Optional<SlideTransition> transitionOut
) {
    public static final Codec<Duration> SECONDS_CODEC = Codec.FLOAT.xmap(
            seconds -> Duration.ofMillis((long) (seconds * 1000)),
            duration -> duration.toMillis() / 1000.0f
    );

    public static final Codec<Slide> CODEC = RecordCodecBuilder.create(i -> i.group(
            SlideContent.MAP_CODEC.forGetter(Slide::content),
            SlideTransition.CODEC.optionalFieldOf("transition_in").forGetter(Slide::transitionIn),
            SlideTransition.CODEC.optionalFieldOf("transition_out").forGetter(Slide::transitionOut)
    ).apply(i, Slide::new));

    public Duration duration() {
        return content.duration();
    }
}
