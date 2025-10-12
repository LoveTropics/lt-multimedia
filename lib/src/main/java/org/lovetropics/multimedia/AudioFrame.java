package org.lovetropics.multimedia;

import java.nio.ByteBuffer;

public interface AudioFrame extends AutoCloseable {
    double presentTime();

    int samples();

    int bytes();

    void unpackSamples(ByteBuffer output) throws DecoderException;

    @Override
    void close();
}
