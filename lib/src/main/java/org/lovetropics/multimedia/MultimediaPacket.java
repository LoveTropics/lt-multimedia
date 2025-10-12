package org.lovetropics.multimedia;

public sealed interface MultimediaPacket extends AutoCloseable permits AudioPacket, VideoPacket {
    @Override
    void close();
}
