package org.lovetropics.multimedia;

import org.jspecify.annotations.Nullable;

public interface VideoDecoder extends AutoCloseable {
    int width();

    int height();

    void sendPacket(VideoPacket packet) throws DecoderException;

    @Nullable
    VideoFrame readFrame() throws DecoderException;

    @Override
    void close();
}
