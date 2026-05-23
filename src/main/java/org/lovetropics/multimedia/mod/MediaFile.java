package org.lovetropics.multimedia.mod;

import com.google.common.collect.ImmutableSet;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Util;
import net.neoforged.fml.loading.FMLEnvironment;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

public record MediaFile(
        URI uri
) {
    public static final boolean CAN_PLAY_FROM_LOCAL_FILE = !FMLEnvironment.isProduction();

    private static final Set<String> ALLOWED_SCHEMES = Util.make(() -> {
        ImmutableSet.Builder<String> schemes = ImmutableSet.builder();
        schemes.add("https");
        if (CAN_PLAY_FROM_LOCAL_FILE) {
            schemes.add("file");
        }
        return schemes.build();
    });

    public static final Codec<URI> URI_CODEC = Codec.STRING.comapFlatMap(string -> {
        try {
            return DataResult.success(parseAndValidateUri(string));
        } catch (URISyntaxException e) {
            return DataResult.error(e::getMessage);
        }
    }, URI::toString);

    public static final Codec<MediaFile> CODEC = URI_CODEC.xmap(MediaFile::new, MediaFile::uri);

    public static final StreamCodec<ByteBuf, MediaFile> STREAM_CODEC = ByteBufCodecs.stringUtf8(512).map(
            string -> {
                try {
                    return new MediaFile(parseAndValidateUri(string));
                } catch (final URISyntaxException e) {
                    throw new DecoderException(e);
                }
            },
            file -> file.uri().toString()
    );

    private static URI parseAndValidateUri(final String string) throws URISyntaxException {
        final URI uri = new URI(string);
        final String scheme = uri.getScheme();
        if (scheme == null) {
            throw new URISyntaxException(string, "Missing protocol in URI: " + string);
        }
        if (!ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new URISyntaxException(string, "Unsupported protocol in URI: " + string);
        }
        return uri;
    }

    public boolean isLocalFile() {
        return uri.getScheme().equals("file");
    }
}
