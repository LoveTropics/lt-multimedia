package org.lovetropics.multimedia.mod.slideshow;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;
import org.lovetropics.multimedia.mod.MediaFile;
import org.lovetropics.multimedia.mod.client.cache.MediaFileCache;

import java.util.List;
import java.util.stream.Stream;

public record Slideshow(
        List<Slide> slides,
        SlideTransition defaultTransition
) {
    public static final Codec<Slideshow> CODEC = RecordCodecBuilder.create(i -> i.group(
            ExtraCodecs.nonEmptyList(Slide.CODEC.listOf()).fieldOf("slides").forGetter(Slideshow::slides),
            SlideTransition.CODEC.optionalFieldOf("default_transition", SlideTransition.NONE).forGetter(Slideshow::defaultTransition)
    ).apply(i, Slideshow::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, Slideshow> STREAM_CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC);

    public Stream<MediaFile> files() {
        return slides.stream()
                .flatMap(slide -> slide.content().files())
                .distinct();
    }

    public void ensureDownloaded(final MediaFileCache mediaCache) {
        files().forEach(mediaCache::ensureDownloaded);
    }
}
