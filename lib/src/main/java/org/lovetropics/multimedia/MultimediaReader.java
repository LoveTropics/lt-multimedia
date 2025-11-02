package org.lovetropics.multimedia;

import org.jspecify.annotations.Nullable;

import javax.sound.sampled.AudioFormat;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.Objects;

public final class MultimediaReader implements Closeable {
    private final long handle;
    private final double duration;
    private boolean closed;

    private MultimediaReader(final long handle) {
        this.handle = handle;
        duration = MultimediaNative.getDuration(handle);
    }

    public static boolean isSupportedPlatform() {
        return Platform.tryDetect() != null;
    }

    public static MultimediaReader open(final Path path) throws IOException {
        Objects.requireNonNull(path);
        final String pathString = path.toAbsolutePath().normalize().toString();
        return new MultimediaReader(MultimediaNative.openPathReader(pathString));
    }

    public static MultimediaReader open(final InputStream input) throws IOException {
        return open(Channels.newChannel(input));
    }

    public static MultimediaReader open(final ReadableByteChannel channel) throws IOException {
        Objects.requireNonNull(channel);
        if (channel instanceof final SeekableByteChannel seekableChannel) {
            return new MultimediaReader(MultimediaNative.openSeekableByteChannelReader(seekableChannel));
        } else {
            return new MultimediaReader(MultimediaNative.openByteChannelReader(channel));
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("Decoder has already been closed");
        }
    }

    public double duration() {
        return duration;
    }

    public synchronized @Nullable MultimediaPacket readPacket() throws IOException {
        checkOpen();
        final long packet = MultimediaNative.readPacket(handle);
        if (packet == 0) {
            return null;
        }
        final int packetType = MultimediaNative.getPacketType(packet);
        return switch (packetType) {
            case MultimediaNative.PACKET_VIDEO -> new VideoPacketImpl(packet);
            case MultimediaNative.PACKET_AUDIO -> new AudioPacketImpl(packet);
            default -> {
                MultimediaNative.destroyPacket(packet);
                throw new IOException("Unrecognized packet type: " + packetType);
            }
        };
    }

    public synchronized @Nullable VideoDecoder openVideoDecoder() throws IOException, DecoderException {
        checkOpen();
        final long videoDecoder = MultimediaNative.openVideoDecoder(handle);
        return videoDecoder != 0 ? new VideoDecoderImpl(videoDecoder) : null;
    }

    public synchronized @Nullable AudioDecoder openAudioDecoder(final AudioFormat format) throws IOException, DecoderException {
        checkOpen();

        final int sampleFormat = getAudioSampleFormat(format.getEncoding(), format.getSampleSizeInBits());

        final int channels = format.getChannels();
        final boolean stereo;
        if (channels == 1) {
            stereo = false;
        } else if (channels == 2) {
            stereo = true;
        } else {
            throw new IllegalArgumentException("Unsupported channels: " + channels);
        }

        final int sampleRate = (int) format.getSampleRate();
        if (sampleRate != format.getSampleRate() || format.getSampleRate() <= 0) {
            throw new IllegalArgumentException("Sample rate must be a positive integer: " + format.getSampleRate());
        }

        final long audioDecoder = MultimediaNative.openAudioDecoder(handle, sampleFormat, stereo, sampleRate);
        return audioDecoder != 0 ? new AudioDecoderImpl(audioDecoder, format) : null;
    }

    private static int getAudioSampleFormat(final AudioFormat.Encoding encoding, final int sampleBits) {
        if (sampleBits == 8 && encoding == AudioFormat.Encoding.PCM_UNSIGNED) {
            return MultimediaNative.AUDIO_FORMAT_U8;
        }
        if (sampleBits == 16 && encoding == AudioFormat.Encoding.PCM_SIGNED) {
            return MultimediaNative.AUDIO_FORMAT_I16;
        }
        if (sampleBits == 32) {
            if (encoding == AudioFormat.Encoding.PCM_SIGNED) {
                return MultimediaNative.AUDIO_FORMAT_I32;
            } else if (encoding == AudioFormat.Encoding.PCM_FLOAT) {
                return MultimediaNative.AUDIO_FORMAT_F32;
            }
        }
        if (sampleBits == 64) {
            if (encoding == AudioFormat.Encoding.PCM_SIGNED) {
                return MultimediaNative.AUDIO_FORMAT_I64;
            } else if (encoding == AudioFormat.Encoding.PCM_FLOAT) {
                return MultimediaNative.AUDIO_FORMAT_F64;
            }
        }
        throw new IllegalArgumentException("Unsupported encoding: " + encoding + " with " + sampleBits + " bits");
    }

    public synchronized void seekUpTo(final double time) throws IOException {
        checkOpen();
        MultimediaNative.seekUpTo(handle, time);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        MultimediaNative.destroyReader(handle);
    }

    private static class VideoDecoderImpl implements VideoDecoder {
        private final long handle;
        private final int width;
        private final int height;

        private boolean maybeHasFrames;
        private boolean closed;

        private VideoDecoderImpl(final long handle) {
            this.handle = handle;
            width = MultimediaNative.getVideoWidth(handle);
            height = MultimediaNative.getVideoHeight(handle);
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        private void checkOpen() {
            if (closed) {
                throw new IllegalStateException("Decoder has already been closed");
            }
        }

        private void checkCanSendPacket() {
            checkOpen();
            if (maybeHasFrames) {
                throw new IllegalStateException("Cannot send packet before all frames have been drained");
            }
        }

        @Override
        public void sendPacket(final VideoPacket packet) throws DecoderException {
            checkCanSendPacket();
            try (packet) {
                MultimediaNative.sendVideoPacket(handle, ((VideoPacketImpl) packet).handle());
                maybeHasFrames = true;
            }
        }

        @Override
        public @Nullable VideoFrame readFrame() throws DecoderException {
            checkOpen();
            if (!maybeHasFrames) {
                return null;
            }
            final long frameHandle = MultimediaNative.readVideoFrame(handle);
            if (frameHandle == 0) {
                maybeHasFrames = false;
                return null;
            }
            return new VideoFrameImpl(frameHandle);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            MultimediaNative.destroyVideoDecoder(handle);
        }
    }

    private static class VideoFrameImpl implements VideoFrame {
        private final long handle;
        private final double presentTime;
        private final double presentEndTime;
        private boolean closed;

        private VideoFrameImpl(final long handle) {
            this.handle = handle;
            presentTime = MultimediaNative.getVideoFramePresentTime(handle);
            presentEndTime = MultimediaNative.getVideoFramePresentEndTime(handle);
        }

        @Override
        public double presentTime() {
            return presentTime;
        }

        @Override
        public double presentEndTime() {
            return presentEndTime;
        }

        @Override
        public void unpackPixels(final int outputWidth, final int outputHeight, final ByteBuffer output) throws DecoderException {
            if (closed) {
                throw new IllegalStateException("Frame has already been closed");
            }
            closed = true;
            if (outputWidth <= 0 || outputHeight <= 0) {
                throw new IllegalArgumentException("Output width and height must be positive");
            }
            final int bytes = outputWidth * outputHeight * Integer.BYTES;
            if (output.remaining() < bytes) {
                throw new IllegalArgumentException("Output buffer is too small, must have at least " + bytes + " remaining but had " + output.remaining());
            }
            MultimediaNative.unpackVideoPixels(handle, outputWidth, outputHeight, output, output.position());
            output.position(output.position() + bytes);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            MultimediaNative.destroyVideoFrame(handle);
        }
    }

    private static class AudioDecoderImpl implements AudioDecoder {
        private final long handle;
        private final AudioFormat format;

        private boolean maybeHasFrames;
        private boolean closed;

        private AudioDecoderImpl(final long handle, final AudioFormat format) {
            this.handle = handle;
            this.format = format;
        }

        private void checkOpen() {
            if (closed) {
                throw new IllegalStateException("Decoder has already been closed");
            }
        }

        private void checkCanSendPacket() {
            checkOpen();
            if (maybeHasFrames) {
                throw new IllegalStateException("Cannot send packet before all frames have been drained");
            }
        }

        @Override
        public AudioFormat format() {
            return format;
        }

        @Override
        public void sendPacket(final AudioPacket packet) throws DecoderException {
            checkCanSendPacket();
            try (packet) {
                MultimediaNative.sendAudioPacket(handle, ((AudioPacketImpl) packet).handle());
                maybeHasFrames = true;
            }
        }

        @Override
        public @Nullable AudioFrame readFrame() throws DecoderException {
            checkOpen();
            if (!maybeHasFrames) {
                return null;
            }
            final long frameHandle = MultimediaNative.readAudioFrame(handle);
            if (frameHandle == 0) {
                maybeHasFrames = false;
                return null;
            }
            return new AudioFrameImpl(frameHandle);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            MultimediaNative.destroyAudioDecoder(handle);
        }
    }

    private static class AudioFrameImpl implements AudioFrame {
        private final long handle;
        private final double presentTime;
        private final int samples;
        private final int bytes;
        private boolean closed;

        private AudioFrameImpl(final long handle) {
            this.handle = handle;
            presentTime = MultimediaNative.getAudioFramePresentTime(handle);
            samples = MultimediaNative.getAudioFrameSamples(handle);
            bytes = MultimediaNative.getAudioFrameBytes(handle);
        }

        @Override
        public double presentTime() {
            return presentTime;
        }

        @Override
        public int samples() {
            return samples;
        }

        @Override
        public int bytes() {
            return bytes;
        }

        @Override
        public void unpackSamples(final ByteBuffer output) throws DecoderException {
            if (closed) {
                throw new IllegalStateException("Frame has already been closed");
            }
            closed = true;
            if (output.remaining() < bytes) {
                throw new IllegalArgumentException("Output buffer is too small, must have at least " + bytes + " remaining but had " + output.remaining());
            }
            MultimediaNative.unpackAudioSamples(handle, output, output.position());
            output.position(output.position() + bytes);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            MultimediaNative.destroyAudioFrame(handle);
        }
    }

    private static abstract class AbstractPacket implements AutoCloseable {
        private final long handle;
        private boolean closed;

        protected AbstractPacket(final long handle) {
            this.handle = handle;
        }

        public long handle() {
            if (closed) {
                throw new IllegalStateException("Packet has already been freed");
            }
            return handle;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            MultimediaNative.destroyPacket(handle);
        }
    }

    /* package-private*/ static final class VideoPacketImpl extends AbstractPacket implements VideoPacket {
        private VideoPacketImpl(final long handle) {
            super(handle);
        }
    }

    /* package-private*/ static final class AudioPacketImpl extends AbstractPacket implements AudioPacket {
        private AudioPacketImpl(final long handle) {
            super(handle);
        }
    }
}
