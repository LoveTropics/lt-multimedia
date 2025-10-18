package org.lovetropics.multimedia.mod;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.Util;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

public record MediaFile(
        URI uri
) {
    private static final Set<String> ALLOWED_SCHEMES = Set.of("https");

    public static final Codec<URI> URI_CODEC = ExtraCodecs.UNTRUSTED_URI.validate(MediaFile::validateUri);

    public static final Codec<MediaFile> CODEC = URI_CODEC.xmap(MediaFile::new, MediaFile::uri);

    public static final StreamCodec<ByteBuf, MediaFile> STREAM_CODEC = ByteBufCodecs.stringUtf8(512).map(
            string -> {
                try {
                    final URI uri = validateUri(
                            Util.parseAndValidateUntrustedUri(string)
                    ).getOrThrow(DecoderException::new);
                    return new MediaFile(uri);
                } catch (final URISyntaxException e) {
                    throw new DecoderException(e);
                }
            },
            file -> file.uri().toString()
    );

    private static DataResult<URI> validateUri(final URI uri) {
        final String scheme = uri.getScheme();
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            return DataResult.error(() -> "Invalid URL scheme: " + scheme);
        }
        return DataResult.success(uri);
    }
}
