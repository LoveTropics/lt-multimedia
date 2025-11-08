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
import org.lovetropics.multimedia.mod.slideshow.SlideContent;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/* package-private */ class AudioPlaybackChannel extends Channel {
    private static final Logger LOGGER = LogUtils.getLogger();

    // Ensure we have enough audio to fill the space between ticks, even at a low tick/frame rate
    private static final double QUEUE_AT_LEAST_SECONDS = 0.25;

    private static final double MAX_CLOCK_DRIFT = 0.01;
    private static final double CORRECT_OVER_SECONDS = 5.0;

    private final SoundManager soundManager;
    private final PacketReader packets;
    private final AudioDecoder decoder;
    private final ClockSyncer clock;

    private final AudioSampler sampler;

    private final Deque<QueuedFrame> queuedFrames = new ArrayDeque<>();
    private double lastPlayedFrameEndTime;

    @Nullable
    private ClockCorrection activeCorrection;

    @Nullable
    private AudioWorldSource worldSource;

    private volatile boolean seeking;

    private volatile boolean closed;

    private AudioPlaybackChannel(final int source, final SoundManager soundManager, final PacketReader packets, final AudioDecoder decoder, final ClockSyncer clock) {
        super(source);
        this.soundManager = soundManager;
        this.packets = packets;
        this.decoder = decoder;
        this.clock = clock;
        sampler = new AudioSampler(decoder.format());
        setupSource();
    }

    private void setupSource() {
        AL10.alSourcef(source, AL10.AL_MAX_GAIN, SlideContent.MAX_VOLUME);
        setVolume(1.0f);
        setPitch(1.0f);
        disableAttenuation();
        setLooping(false);
        setSelfPosition(Vec3.ZERO);
        setRelative(true);
    }

    public static Handle create(final SoundManager soundManager, final PacketReader packets, final AudioDecoder decoder, final ClockSyncer clock) {
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
        unqueueBuffers(queuedFrames.size());
        queuedFrames.clear();
        lastPlayedFrameEndTime = 0.0;
        activeCorrection = null;
    }

    public void endSeek() {
        if (!seeking) {
            throw new IllegalStateException("Not seeking");
        }
        seeking = false;
        lastPlayedFrameEndTime = clock.getElapsedTime();
        updateStream();
    }

    // ChannelAccess will delete this channel if it ever reports being stopped - we might pause for a bit if we're
    // lagging behind, but we don't want to actually stop until the playback is over.
    @Override
    public boolean stopped() {
        return closed;
    }

    @Override
    public void updateStream() {
        if (closed || seeking) {
            return;
        }

        if (worldSource != null) {
            final ListenerTransform listenerTransform = soundManager.getListenerTransform();
            setSelfPosition(worldSource.resolveSourcePos(listenerTransform));
        }

        final boolean stopped = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED;
        final int processedBuffers = stopped && clock.isPaused() ? 0 : removeProcessedBuffers();
        for (int i = 0; i < processedBuffers; i++) {
            lastPlayedFrameEndTime = queuedFrames.removeFirst().endTime();
        }

        if (playing() && clock.isPaused()) {
            pause();
        }

        final double speedFactor = syncClocks(getAudioClockTime());
        tryQueueFrames(lastPlayedFrameEndTime + QUEUE_AT_LEAST_SECONDS, speedFactor);

        if (!playing() && !clock.isPaused() && !queuedFrames.isEmpty()) {
            play();
        }
    }

    private double getAudioClockTime() {
        if (queuedFrames.isEmpty()) {
            return lastPlayedFrameEndTime;
        }
        // If we progressed past the first queued frame, this might be inaccurate - but we just dequeued, so it's a very good guess
        final double currentSpeedFactor = queuedFrames.peekFirst().speedFactor();
        return lastPlayedFrameEndTime + AL10.alGetSourcef(source, AL11.AL_SEC_OFFSET) / currentSpeedFactor;
    }

    private double syncClocks(final double audioClockTime) {
        if (clock.requestSyncTo(audioClockTime)) {
            return 1.0;
        }

        // Audio is not authoritative, sync to clock
        final double clockTime = clock.getElapsedTime();
        final double clockDrift = clockTime - audioClockTime;

        if (activeCorrection != null && (clockTime > activeCorrection.finishAt || Math.abs(clockDrift) < MAX_CLOCK_DRIFT)) {
            activeCorrection = null;
        }

        if (activeCorrection == null && Math.abs(clockDrift) > MAX_CLOCK_DRIFT) {
            activeCorrection = new ClockCorrection(
                    Mth.clamp(1.0 + clockDrift / CORRECT_OVER_SECONDS, 0.5, 2.0),
                    clockTime + CORRECT_OVER_SECONDS / 2.0
            );
        }

        return activeCorrection != null ? activeCorrection.speedFactor : 1.0;
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
        while (queuedFrames.isEmpty() || queuedFrames.getLast().endTime() < queueUntilTime) {
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
                queuedFrames.addLast(new QueuedFrame(frameEndTime, speedFactor));
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

        private void execute(final Consumer<AudioPlaybackChannel> consumer) {
            inner.thenAcceptAsync(consumer, channelAccess.executor);
        }

        public void setVolume(final float volume) {
            execute(channel -> channel.setVolume(volume));
        }

        public void setWorldSource(final AudioWorldSource source) {
            execute(channel -> channel.setWorldSource(source));
        }

        public void beginSeek() {
            // Don't wait for scheduling, stop processing audio frames immediately
            inner.join().seeking = true;
            execute(AudioPlaybackChannel::reset);
        }

        public void endSeek() {
            execute(AudioPlaybackChannel::endSeek);
        }

        @Override
        public void close() {
            execute(AudioPlaybackChannel::scheduleClose);
        }
    }

    private record QueuedFrame(double endTime, double speedFactor) {
    }
}
