package org.lovetropics.multimedia.mod.client.playback;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.lovetropics.multimedia.AudioDecoder;
import org.lovetropics.multimedia.DecoderException;
import org.lovetropics.multimedia.MultimediaReader;
import org.lovetropics.multimedia.VideoDecoder;

import javax.annotation.Nullable;
import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;

public class Playback implements AutoCloseable {
    /* package-private */ static final Thread.Builder IO_THREAD_BUILDER = Thread.ofPlatform()
            .name("video-io-", 0)
            .daemon(true);
    /* package-private */ static final Thread.Builder VIDEO_DECODER_THREAD_BUILDER = Thread.ofPlatform()
            .name("video-decoder-", 0)
            .daemon(true);

    private static final AudioFormat AUDIO_FORMAT = new AudioFormat(44100, 16, 2, true, false);

    private final FrameSize sourceFrameSize;

    private final PacketReader packetReader;
    private final PlaybackVideoDecoder videoDecoder;
    private final VideoFrameUploader videoFrameUploader;
    @Nullable
    private final AudioPlaybackChannel.Handle audioPlayback;

    private final VideoFrameTexture texture;

    private final PlaybackClock clock = new PlaybackClock();

    private Playback(
            final MultimediaReader reader,
            final VideoDecoder videoDecoder,
            @Nullable final AudioDecoder audioDecoder,
            final FrameSize windowSize
    ) {
        sourceFrameSize = new FrameSize(videoDecoder.width(), videoDecoder.height());
        packetReader = new PacketReader(reader);
        videoFrameUploader = new VideoFrameUploader(RenderSystem.getDevice(), fitTextureSize(windowSize), clock);
        this.videoDecoder = new PlaybackVideoDecoder(packetReader, videoDecoder, videoFrameUploader);
        if (audioDecoder != null) {
            audioPlayback = AudioPlaybackChannel.create(Minecraft.getInstance().getSoundManager(), packetReader, audioDecoder, clock);
        } else {
            audioPlayback = null;
        }

        texture = VideoFrameTexture.register(Minecraft.getInstance().getTextureManager());

        PlaybackManager.register(this);
    }

    public static Playback open(final Path path, final FrameSize windowSize) throws IOException, DecoderException {
        return open(MultimediaReader.open(path), windowSize);
    }

    public static Playback open(final InputStream input, final FrameSize windowSize) throws IOException, DecoderException {
        return open(MultimediaReader.open(input), windowSize);
    }

    public static Playback open(final SeekableByteChannel channel, final FrameSize windowSize) throws IOException, DecoderException {
        return open(MultimediaReader.open(channel), windowSize);
    }

    private static Playback open(final MultimediaReader reader, final FrameSize windowSize) throws IOException, DecoderException {
        final VideoDecoder videoDecoder = reader.openVideoDecoder();
        if (videoDecoder == null) {
            throw new IOException("Media has no video stream");
        }
        final AudioDecoder audioDecoder = reader.openAudioDecoder(AUDIO_FORMAT);
        return new Playback(reader, videoDecoder, audioDecoder, windowSize);
    }

    private FrameSize fitTextureSize(final FrameSize windowSize) {
        final FrameSize frameSize = sourceFrameSize.fitInto(windowSize);
        if (frameSize.width() > 128) {
            // ffmpeg prefers frame strides aligned to 32 bytes
            return frameSize.resizeWidth(frameSize.width() & ~31);
        }
        return frameSize;
    }

    public void updateWindowSize(final FrameSize windowSize) {
        videoFrameUploader.requestFrameSize(fitTextureSize(windowSize));
    }

    public void play() {
        if (!clock.isPaused()) {
            return;
        }
        if (audioPlayback != null) {
            audioPlayback.execute(channel -> {
                channel.play();
                clock.play();
            });
        } else {
            clock.pause();
        }
    }

    public void pause() {
        if (clock.isPaused()) {
            return;
        }
        if (audioPlayback != null) {
            audioPlayback.execute(channel -> {
                channel.pause();
                clock.pause();
            });
        } else {
            clock.pause();
        }
    }

    public boolean hasStopped() {
        return videoDecoder.isClosed() && (audioPlayback == null || audioPlayback.isStopped());
    }

    @Nullable
    public VideoFrameTexture updateTexture(final GpuDevice device) {
        final PresentableVideoFrame nextFrame = videoFrameUploader.takeNextFrame();
        if (nextFrame != null) {
            try (nextFrame) {
                texture.copyFrom(device, nextFrame);
            }
        }
        return texture.hasFrame() ? texture : null;
    }

    /* package-private */ void endFrame() {
        videoFrameUploader.tick();
    }

    @Override
    public void close() {
        PlaybackManager.unregister(this);
        clock.pause();
        packetReader.close();
        videoFrameUploader.close();
        videoDecoder.close();
        if (audioPlayback != null) {
            audioPlayback.close();
        }
        Minecraft.getInstance().getTextureManager().release(texture.location());
    }
}
