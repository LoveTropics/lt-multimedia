package org.lovetropics.multimedia.mod.client.playback;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import org.lovetropics.multimedia.AudioDecoder;
import org.lovetropics.multimedia.DecoderException;
import org.lovetropics.multimedia.MultimediaReader;
import org.lovetropics.multimedia.VideoDecoder;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

public class Playback implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();

    /* package-private */ static final Thread.Builder IO_THREAD_BUILDER = Thread.ofPlatform()
            .name("video-io-", 0)
            .daemon(true);
    /* package-private */ static final Thread.Builder VIDEO_DECODER_THREAD_BUILDER = Thread.ofPlatform()
            .name("video-decoder-", 0)
            .daemon(true);

    private static final AudioFormat MONO_AUDIO_FORMAT = new AudioFormat(44100, 16, 1, true, false);

    private final FrameSize sourceFrameSize;
    private final double sourceDuration;

    private final PlaybackClock clock;

    private final PacketReader packetReader;
    private final PlaybackVideoDecoder videoDecoder;
    private final VideoFrameUploader videoFrameUploader;
    @Nullable
    private final AudioPlaybackChannel.Handle audioPlayback;

    private final VideoFrameTexture texture;

    private final ReentrantLock seekLock = new ReentrantLock();
    private int seekCount;

    private Playback(
            final MultimediaReader reader,
            final VideoDecoder videoDecoder,
            @Nullable final AudioDecoder audioDecoder,
            @Nullable final FrameSize windowSize,
            final PlaybackSyncType syncType
    ) {
        sourceFrameSize = new FrameSize(videoDecoder.width(), videoDecoder.height());
        sourceDuration = reader.duration();

        clock = new PlaybackClock();

        packetReader = new PacketReader(reader);
        videoFrameUploader = new VideoFrameUploader(RenderSystem.getDevice(), fitTextureSize(windowSize), clock.createSyncer(false));
        this.videoDecoder = new PlaybackVideoDecoder(packetReader, videoDecoder, videoFrameUploader);
        if (audioDecoder != null) {
            audioPlayback = AudioPlaybackChannel.create(Minecraft.getInstance().getSoundManager(), packetReader, audioDecoder, syncType.createAudioSyncer(clock));
        } else {
            audioPlayback = null;
        }

        texture = VideoFrameTexture.register(Minecraft.getInstance().getTextureManager());

        PlaybackManager.register(this);
    }

    public static Playback open(final MultimediaReader reader, @Nullable final FrameSize windowSize, final PlaybackSyncType syncType) throws IOException, DecoderException {
        final VideoDecoder videoDecoder = reader.openVideoDecoder();
        if (videoDecoder == null) {
            throw new IOException("Media has no video stream");
        }
        final AudioDecoder audioDecoder = reader.openAudioDecoder(MONO_AUDIO_FORMAT);
        return new Playback(reader, videoDecoder, audioDecoder, windowSize, syncType);
    }

    private FrameSize fitTextureSize(@Nullable final FrameSize windowSize) {
        if (windowSize == null) {
            return sourceFrameSize;
        }
        final FrameSize frameSize = sourceFrameSize.fitInto(windowSize);
        if (frameSize.width() > 128) {
            // ffmpeg prefers frame strides aligned to 32 bytes
            return frameSize.resizeWidth(frameSize.width() & ~31);
        }
        return frameSize;
    }

    public void updateWindowSize(@Nullable final FrameSize windowSize) {
        videoFrameUploader.requestFrameSize(fitTextureSize(windowSize));
    }

    public void setAudioVolume(final float volume) {
        if (audioPlayback != null) {
            audioPlayback.setVolume(volume);
        }
    }

    public void setAudioSource(final AudioWorldSource source) {
        if (audioPlayback != null) {
            audioPlayback.setWorldSource(source);
        }
    }

    public void play() {
        clock.play();
    }

    public void pause() {
        clock.pause();
    }

    public void seekTo(final double time) {
        if (Mth.equal(time, clock.getElapsedTime())) {
            return;
        } else if (!clock.isPaused() && Math.abs(time - clock.getElapsedTime()) < 0.5) {
            // Within a small enough margin, slow down or speed up frames to get to our target
            clock.setElapsedTime(time);
            return;
        }

        beginSeek();
        clock.setElapsedTime(time);

        packetReader.seekTo(Mth.clamp(time, 0.0, sourceDuration)).whenComplete((unused, throwable) -> {
            endSeek();
            if (throwable != null) {
                LOGGER.error("An error occurred while seeking to {}", time, throwable);
            }
        });
    }

    private void beginSeek() {
        seekLock.lock();
        try {
            if (seekCount++ == 0) {
                videoFrameUploader.beginSeek();
                if (audioPlayback != null) {
                    audioPlayback.beginSeek();
                }
            }
        } finally {
            seekLock.unlock();
        }
    }

    private void endSeek() {
        seekLock.lock();
        try {
            if (--seekCount == 0) {
                videoFrameUploader.endSeek();
                if (audioPlayback != null) {
                    audioPlayback.endSeek();
                }
            }
        } finally {
            seekLock.unlock();
        }
    }

    public double currentTime() {
        return clock.getElapsedTime();
    }

    public double duration() {
        return sourceDuration;
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
        packetReader.close();
        videoFrameUploader.close();
        videoDecoder.close();
        if (audioPlayback != null) {
            audioPlayback.close();
        }
        Minecraft.getInstance().getTextureManager().release(texture.location());
    }
}
