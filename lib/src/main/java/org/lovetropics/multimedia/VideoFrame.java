package org.lovetropics.multimedia;

import java.nio.ByteBuffer;

public interface VideoFrame extends AutoCloseable {
    double presentTime();

    double presentEndTime();

    void unpackPixels(int outputWidth, int outputHeight, ByteBuffer output) throws DecoderException;

    @Override
    void close();
}
