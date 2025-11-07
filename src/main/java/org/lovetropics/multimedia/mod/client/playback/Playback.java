package org.lovetropics.multimedia.mod.client.playback;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import org.lovetropics.multimedia.AudioDecoder;
import org.lovetropics.multimedia.DecoderException;
import org.lovetropics.multimedia.MultimediaReader;
import org.lovetropics.multimedia.VideoDecoder;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

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

    private final Queue<Runnable> taskQueue = new ConcurrentLinkedQueue<>();
    private CompletableFuture<?> controlFuture = CompletableFuture.completedFuture(null);

    private Playback(
            final MultimediaReader reader,
            final VideoDecoder videoDecoder,
            @Nullable final AudioDecoder audioDecoder,
            final FrameSize windowSize,
            final PlaybackSyncType syncType
    ) {
        sourceFrameSize = new FrameSize(videoDecoder.width(), videoDecoder.height());
        sourceDuration = reader.duration();

        clock = new PlaybackClock(syncType);

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

    public static Playback open(final SeekableByteChannel channel, final FrameSize windowSize, final PlaybackSyncType syncType) throws IOException, DecoderException {
        return open(MultimediaReader.open(channel), windowSize, syncType);
    }

    private static Playback open(final MultimediaReader reader, final FrameSize windowSize, final PlaybackSyncType syncType) throws IOException, DecoderException {
        final VideoDecoder videoDecoder = reader.openVideoDecoder();
        if (videoDecoder == null) {
            throw new IOException("Media has no video stream");
        }
        final AudioDecoder audioDecoder = reader.openAudioDecoder(MONO_AUDIO_FORMAT);
        return new Playback(reader, videoDecoder, audioDecoder, windowSize, syncType);
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

    public void setAudioVolume(final float volume) {
        if (audioPlayback != null) {
            audioPlayback.execute(channel ->
                    channel.setVolume(volume)
            );
        }
    }

    public void setAudioSource(final AudioWorldSource source) {
        if (audioPlayback != null) {
            audioPlayback.execute(channel ->
                    channel.setWorldSource(source)
            );
        }
    }

    private void scheduleControl(final Supplier<CompletableFuture<?>> task) {
        if (controlFuture.isDone()) {
            controlFuture = task.get();
        } else {
            controlFuture = controlFuture.thenComposeAsync(ignored -> task.get(), taskQueue::add);
        }
    }

    public void play() {
        scheduleControl(this::playInternal);
    }

    private CompletableFuture<?> playInternal() {
        if (!clock.isPaused()) {
            return CompletableFuture.completedFuture(null);
        }
        if (audioPlayback != null) {
            return audioPlayback.execute(channel -> {
                channel.play();
                clock.play();
            });
        } else {
            clock.play();
            return CompletableFuture.completedFuture(null);
        }
    }

    public void pause() {
        scheduleControl(this::pauseInternal);
    }

    private CompletableFuture<?> pauseInternal() {
        if (clock.isPaused()) {
            return CompletableFuture.completedFuture(null);
        }
        if (audioPlayback != null) {
            return audioPlayback.execute(channel -> {
                channel.pause();
                clock.pause();
            });
        } else {
            clock.pause();
            return CompletableFuture.completedFuture(null);
        }
    }

    public void seekTo(final double time) {
        if (time < 0.0 || time > sourceDuration) {
            throw new IllegalArgumentException("Time must be between 0 and " + sourceDuration + " seconds");
        }
        scheduleControl(() -> seekToInternal(time));
    }

    private CompletableFuture<?> seekToInternal(final double time) {
        if (clock.getElapsedTime() == time) {
            return CompletableFuture.completedFuture(null);
        }

        final boolean wasPaused = clock.isPaused();
        clock.pause();

        CompletableFuture<?> future = CompletableFuture.completedFuture(null);

        videoFrameUploader.beginSeek();
        if (audioPlayback != null) {
            future = audioPlayback.execute(AudioPlaybackChannel::beginSeek);
        }

        future = future.thenCompose(ignored -> packetReader.seekTo(time));
        future = future.thenRunAsync(() -> {
            clock.setElapsedTime(time);
            if (!wasPaused) {
                clock.play();
            }
            videoFrameUploader.endSeek();
        }, taskQueue::add);

        if (audioPlayback != null) {
            future = future.thenCompose(ignored ->
                    audioPlayback.execute(AudioPlaybackChannel::endSeek)
            );
        }

        future.exceptionally(throwable -> {
            LOGGER.error("Failed to seek to {}", time, throwable);
            return null;
        });

        return future;
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
        runTasks();
        videoFrameUploader.tick();
    }

    private void runTasks() {
        Runnable task;
        while ((task = taskQueue.poll()) != null) {
            task.run();
        }
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
