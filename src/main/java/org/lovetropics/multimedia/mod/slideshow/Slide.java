package org.lovetropics.multimedia.mod.slideshow;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.StringRepresentable;
import org.lovetropics.multimedia.mod.MediaFile;

import java.time.Duration;
import java.util.Optional;

public sealed interface Slide {
    Codec<Duration> SECONDS_CODEC = Codec.FLOAT.xmap(
            seconds -> Duration.ofMillis((long) (seconds * 1000)),
            duration -> duration.toMillis() / 1000.0f
    );

    float MAX_VOLUME = 10.0f;

    Codec<Slide> CODEC = Type.CODEC.dispatch(Slide::type, type -> type.codec);

    MediaFile file();

    Optional<SlideTransition> transitionIn();

    Optional<SlideTransition> transitionOut();

    Type type();

    record Video(
            MediaFile file,
            Optional<SlideTransition> transitionIn,
            Optional<SlideTransition> transitionOut,
            float volume
    ) implements Slide {
        public static final MapCodec<Video> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                MediaFile.CODEC.fieldOf("file").forGetter(Video::file),
                SlideTransition.CODEC.optionalFieldOf("transition_in").forGetter(Video::transitionIn),
                SlideTransition.CODEC.optionalFieldOf("transition_out").forGetter(Video::transitionOut),
                Codec.floatRange(0.0f, MAX_VOLUME).optionalFieldOf("volume", 1.0f).forGetter(Video::volume)
        ).apply(i, Video::new));

        @Override
        public Type type() {
            return Type.VIDEO;
        }
    }

    enum Type implements StringRepresentable {
        VIDEO("video", Video.MAP_CODEC),
        ;

        public static final Codec<Type> CODEC = StringRepresentable.fromEnum(Type::values);

        private final String name;
        private final MapCodec<? extends Slide> codec;

        Type(final String name, final MapCodec<? extends Slide> codec) {
            this.name = name;
            this.codec = codec;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}
