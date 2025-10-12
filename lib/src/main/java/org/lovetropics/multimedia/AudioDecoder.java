package org.lovetropics.multimedia;

import org.jspecify.annotations.Nullable;

import javax.sound.sampled.AudioFormat;
import java.io.Closeable;

public interface AudioDecoder extends Closeable {
    AudioFormat format();

    void sendPacket(AudioPacket packet) throws DecoderException;

    @Nullable
    AudioFrame readFrame() throws DecoderException;

    @Override
    void close();
}
