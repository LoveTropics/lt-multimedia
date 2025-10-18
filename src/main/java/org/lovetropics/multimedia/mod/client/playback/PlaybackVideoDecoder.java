package org.lovetropics.multimedia.mod.client.playback;

import com.mojang.logging.LogUtils;
import org.lovetropics.multimedia.DecoderException;
import org.lovetropics.multimedia.VideoDecoder;
import org.lovetropics.multimedia.VideoFrame;
import org.lovetropics.multimedia.VideoPacket;
import org.slf4j.Logger;

/* package-private */ class PlaybackVideoDecoder implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final PacketReader packets;
    private final VideoDecoder decoder;
    private final VideoFrameUploader uploader;
    private final Thread thread;

    private volatile boolean closed;

    public PlaybackVideoDecoder(final PacketReader packets, final VideoDecoder decoder, final VideoFrameUploader uploader) {
        this.packets = packets;
        this.decoder = decoder;
        this.uploader = uploader;
        thread = Playback.VIDEO_DECODER_THREAD_BUILDER.start(this::run);
    }

    private void run() {
        try {
            while (!closed) {
                final VideoFrame frame = decoder.readFrame();
                if (frame == null) {
                    final VideoPacket packet = packets.takeVideoPacket();
                    if (packet == null) {
                        break;
                    }
                    try (packet) {
                        decoder.sendPacket(packet);
                    }
                    continue;
                }
                uploader.writeFrame(frame);
            }
        } catch (final DecoderException e) {
            LOGGER.error("Failed to read from video stream", e);
        } catch (final InterruptedException ignored) {
            // Closed from the main thread
        } finally {
            packets.discardVideo();
            closed = true;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        thread.interrupt();
    }

    public boolean isClosed() {
        return closed;
    }
}
