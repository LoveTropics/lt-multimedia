package org.lovetropics.multimedia.mod.client.cache;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.ExtraCodecs;
import org.lovetropics.multimedia.mod.MediaFile;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/* package-private */ record MediaFileId(
        URI uri,
        Optional<String> etag,
        long size,
        Instant resolvedAt
) {
    public static final MapCodec<MediaFileId> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            MediaFile.URI_CODEC.fieldOf("uri").forGetter(MediaFileId::uri),
            Codec.STRING.optionalFieldOf("etag").forGetter(MediaFileId::etag),
            Codec.LONG.fieldOf("size").forGetter(MediaFileId::size),
            ExtraCodecs.INSTANT_ISO8601.fieldOf("resolved_at").forGetter(MediaFileId::resolvedAt)
    ).apply(i, MediaFileId::new));

    public Duration getCurrentAge() {
        return Duration.between(resolvedAt, Instant.now());
    }
}
