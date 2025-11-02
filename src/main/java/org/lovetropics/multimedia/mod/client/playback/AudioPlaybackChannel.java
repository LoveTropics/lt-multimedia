package org.lovetropics.multimedia.mod.client.playback;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.ListenerTransform;
import com.mojang.blaze3d.audio.OpenAlUtil;
import com.mojang.blaze3d.audio.SoundBuffer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lovetropics.multimedia.AudioDecoder;
import org.lovetropics.multimedia.AudioFrame;
import org.lovetropics.multimedia.AudioPacket;
import org.lovetropics.multimedia.DecoderException;
import org.lovetropics.multimedia.mod.slideshow.Slide;
import org.lwjgl.openal.AL10;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/* package-private */ class AudioPlaybackChannel extends Channel {
    private static final Logger LOGGER = LogUtils.getLogger();

    // Ensure we have enough audio to fill the space between ticks, even at a low tick/frame rate
    private static final double QUEUE_AT_LEAST_SECONDS = 0.25;

    private static final double MAX_CLOCK_DRIFT = 0.1;
    private static final double CORRECT_OVER_SECONDS = 5.0;

    private final SoundManager soundManager;
    private final PacketReader packets;
    private final AudioDecoder decoder;
    private final PlaybackClock clock;

    private final AudioSampler sampler;

    private final Deque<Double> queuedFrameEndTimes = new ArrayDeque<>();
    private double lastPlayedFrameEndTime;

    @Nullable
    private ClockCorrection activeCorrection;

    @Nullable
    private AudioWorldSource worldSource;

    private volatile boolean closed;

    private AudioPlaybackChannel(final int source, final SoundManager soundManager, final PacketReader packets, final AudioDecoder decoder, final PlaybackClock clock) {
        super(source);
        this.soundManager = soundManager;
        this.packets = packets;
        this.decoder = decoder;
        this.clock = clock;
        sampler = new AudioSampler(decoder.format());
        setupSource();
    }

    private void setupSource() {
        AL10.alSourcef(source, AL10.AL_MAX_GAIN, Slide.MAX_VOLUME);
        setVolume(1.0f);
        setPitch(1.0f);
        disableAttenuation();
        setLooping(false);
        setSelfPosition(Vec3.ZERO);
        setRelative(true);
    }

    public static Handle create(final SoundManager soundManager, final PacketReader packets, final AudioDecoder decoder, final PlaybackClock clock) {
        final ChannelAccess channelAccess = soundManager.soundEngine.channelAccess;
        final CompletableFuture<AudioPlaybackChannel> future = CompletableFuture.supplyAsync(() -> {
            final int[] sources = new int[1];
            AL10.alGenSources(sources);
            if (OpenAlUtil.checkALError("Playback audio source")) {
                return null;
            }
            final AudioPlaybackChannel channel = new AudioPlaybackChannel(sources[0], soundManager, packets, decoder, clock);
            channelAccess.channels.add(createChannelHandle(channelAccess, channel));
            return channel;
        }, channelAccess.executor);
        return new Handle(channelAccess, future);
    }

    private static ChannelAccess.ChannelHandle createChannelHandle(final ChannelAccess channelAccess, final Channel channel) {
        return channelAccess.new ChannelHandle(channel) {
            private boolean destroyed;

            @Override
            public boolean isStopped() {
                return destroyed;
            }

            @Override
            public void execute(final Consumer<Channel> consumer) {
                channelAccess.executor.execute(() -> {
                    if (!destroyed) {
                        consumer.accept(channel);
                    }
                });
            }

            @Override
            public void release() {
                destroyed = true;
                channel.destroy();
            }
        };
    }

    public void setWorldSource(final AudioWorldSource worldSource) {
        this.worldSource = worldSource;
        final ListenerTransform listenerTransform = soundManager.getListenerTransform();
        linearAttenuation(worldSource.attenuationDistance());
        setSelfPosition(worldSource.resolveSourcePos(listenerTransform));
        setRelative(false);
    }

    private void reset() {
        // Bit of a hack - the source must not be in the AL_INITIAL state in order to unqueue its buffers
        if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_INITIAL) {
            play();
        }
        stop();
        unqueueBuffers(queuedFrameEndTimes.size());
        queuedFrameEndTimes.clear();
        lastPlayedFrameEndTime = 0.0;
        activeCorrection = null;
    }

    // ChannelAccess will delete this channel if it ever reports being stopped - we might pause for a bit if we're
    // lagging behind, but we don't want to actually stop until the playback is over.
    @Override
    public boolean stopped() {
        return closed;
    }

    @Override
    public void updateStream() {
        if (closed) {
            return;
        }

        if (worldSource != null) {
            final ListenerTransform listenerTransform = soundManager.getListenerTransform();
            setSelfPosition(worldSource.resolveSourcePos(listenerTransform));
        }

        final int processedBuffers = playing() ? removeProcessedBuffers() : 0;
        for (int i = 0; i < processedBuffers; i++) {
            lastPlayedFrameEndTime = queuedFrameEndTimes.removeFirst();
        }

        final double speedFactor = syncClocks();
        tryQueueFrames(lastPlayedFrameEndTime + QUEUE_AT_LEAST_SECONDS, speedFactor);

        if (!playing() && !clock.isPaused() && !queuedFrameEndTimes.isEmpty()) {
            // Somehow ran out of samples, but we're ready to resume again
            play();
        }
    }

    private double syncClocks() {
        if (clock.syncType() == PlaybackSyncType.AUDIO) {
            // The clock should always be somewhere within the frame that we know to be currently playing
            final double currentFrameEndTime = Objects.requireNonNullElse(queuedFrameEndTimes.peekFirst(), lastPlayedFrameEndTime);
            clock.ensureInRange(lastPlayedFrameEndTime, currentFrameEndTime);

            return 1.0;
        } else {
            final double clockTime = clock.getElapsedTime();
            if (activeCorrection != null && clockTime > activeCorrection.finishAt) {
                activeCorrection = null;
            }

            final double clockDrift = clockTime - lastPlayedFrameEndTime;
            if (activeCorrection == null && Math.abs(clockDrift) > MAX_CLOCK_DRIFT) {
                activeCorrection = new ClockCorrection(
                        Mth.clamp(1.0 + clockDrift / CORRECT_OVER_SECONDS, 0.5, 2.0),
                        clockTime + CORRECT_OVER_SECONDS / 2.0
                );
            }

            return activeCorrection != null ? activeCorrection.speedFactor : 1.0;
        }
    }

    private void tryQueueFrames(final double queueUntilTime, final double speedFactor) {
        try {
            queueFrames(queueUntilTime, speedFactor);
        } catch (final DecoderException e) {
            LOGGER.error("Failed to read from audio stream", e);
            scheduleClose();
        }
    }

    private void queueFrames(final double queueUntilTime, final double speedFactor) throws DecoderException {
        while (queuedFrameEndTimes.isEmpty() || queuedFrameEndTimes.getLast() < queueUntilTime) {
            final AudioFrame frame = decoder.readFrame();
            if (frame != null) {
                queueFrame(frame, speedFactor);
                continue;
            }
            final AudioPacket packet = packets.pollAudioPacket();
            if (packet == null) {
                break;
            }
            try (packet) {
                decoder.sendPacket(packet);
            }
        }
    }

    private void queueFrame(final AudioFrame frame, final double speedFactor) throws DecoderException {
        try (frame) {
            final double frameDuration = (double) frame.samples() / decoder.format().getSampleRate();
            final double frameEndTime = frame.presentTime() + frameDuration;
            final ByteBuffer buffer = sampler.sample(frame, Mth.floor(frame.samples() / speedFactor));
            new SoundBuffer(buffer, decoder.format()).releaseAlBuffer().ifPresent(id -> {
                AL10.alSourceQueueBuffers(source, new int[]{id});
                queuedFrameEndTimes.addLast(frameEndTime);
            });
        }
    }

    private int removeProcessedBuffers() {
        final int bufferCount = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        unqueueBuffers(bufferCount);
        return bufferCount;
    }

    private void unqueueBuffers(final int bufferCount) {
        if (bufferCount == 0) {
            return;
        }
        final int[] buffers = new int[bufferCount];
        AL10.alSourceUnqueueBuffers(source, buffers);
        OpenAlUtil.checkALError("Unqueue buffers");
        AL10.alDeleteBuffers(buffers);
        OpenAlUtil.checkALError("Remove processed buffers");
    }

    public void scheduleClose() {
        if (closed) {
            return;
        }
        packets.discardAudio();
        stop();
        // ChannelAccess will clean up the channel on its own, as we now report it as stopped
        // The decoder will be cleaned up from the sound engine thread along with it
        closed = true;
    }

    @Override
    public void destroy() {
        reset();
        super.destroy();
        decoder.close();
    }

    private record ClockCorrection(double speedFactor, double finishAt) {
    }

    /* package-private */ static class Handle implements AutoCloseable {
        private final ChannelAccess channelAccess;
        private final CompletableFuture<AudioPlaybackChannel> inner;

        private Handle(final ChannelAccess channelAccess, final CompletableFuture<AudioPlaybackChannel> inner) {
            this.channelAccess = channelAccess;
            this.inner = inner;
        }

        public void execute(final Consumer<AudioPlaybackChannel> consumer) {
            inner.thenAcceptAsync(consumer, channelAccess.executor);
        }

        @Override
        public void close() {
            execute(AudioPlaybackChannel::scheduleClose);
        }
    }
}
