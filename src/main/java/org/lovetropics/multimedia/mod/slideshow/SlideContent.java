package org.lovetropics.multimedia.mod.slideshow;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.StringRepresentable;
import org.lovetropics.multimedia.mod.MediaFile;

import java.time.Duration;
import java.util.stream.Stream;

public sealed interface SlideContent {
    float MAX_VOLUME = 10.0f;

    MapCodec<SlideContent> MAP_CODEC = Type.CODEC.dispatchMap(SlideContent::type, type -> type.codec);

    Stream<MediaFile> files();

    Type type();

    record Video(
            MediaFile file,
            Duration startAt,
            float volume
    ) implements SlideContent {
        public static final MapCodec<Video> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                MediaFile.CODEC.fieldOf("file").forGetter(Video::file),
                Slide.SECONDS_CODEC.optionalFieldOf("start_at", Duration.ZERO).forGetter(Video::startAt),
                Codec.floatRange(0.0f, MAX_VOLUME).optionalFieldOf("volume", 1.0f).forGetter(Video::volume)
        ).apply(i, Video::new));

        @Override
        public Stream<MediaFile> files() {
            return Stream.of(file);
        }

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
        private final MapCodec<? extends SlideContent> codec;

        Type(final String name, final MapCodec<? extends SlideContent> codec) {
            this.name = name;
            this.codec = codec;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}
