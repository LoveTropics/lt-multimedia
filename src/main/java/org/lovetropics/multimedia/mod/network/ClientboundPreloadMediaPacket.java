package org.lovetropics.multimedia.mod.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.lovetropics.multimedia.mod.MultimediaMod;
import org.lovetropics.multimedia.mod.client.cache.MediaFileCache;
import org.lovetropics.multimedia.mod.MediaFile;

import java.util.List;

public record ClientboundPreloadMediaPacket(
        List<MediaFile> files
) implements CustomPacketPayload {
    public static final StreamCodec<ByteBuf, ClientboundPreloadMediaPacket> STREAM_CODEC = StreamCodec.composite(
            MediaFile.STREAM_CODEC.apply(ByteBufCodecs.list()), ClientboundPreloadMediaPacket::files,
            ClientboundPreloadMediaPacket::new
    );

    public static final Type<ClientboundPreloadMediaPacket> TYPE = new Type<>(MultimediaMod.location("preload_media"));

    public static void handle(final ClientboundPreloadMediaPacket packet, final IPayloadContext context) {
        final MediaFileCache mediaCache = MultimediaMod.mediaCache();
        for (final MediaFile file : packet.files) {
            mediaCache.ensureDownloaded(file);
        }
    }

    @Override
    public Type<ClientboundPreloadMediaPacket> type() {
        return TYPE;
    }
}
