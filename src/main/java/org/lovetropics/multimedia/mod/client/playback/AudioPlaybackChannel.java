package org.lovetropics.multimedia.mod.client.playback;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.OpenAlUtil;
import com.mojang.blaze3d.audio.SoundBuffer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lovetropics.multimedia.AudioDecoder;
import org.lovetropics.multimedia.AudioFrame;
import org.lovetropics.multimedia.AudioPacket;
import org.lovetropics.multimedia.DecoderException;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
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

    private final SoundManager soundManager;
    private final PacketReader packets;
    private final AudioDecoder decoder;
    private final PlaybackClock clock;

    private final Deque<Double> queuedFrameEndTimes = new ArrayDeque<>();
    private double lastPlayedFrameEndTime;

    @Nullable
    private AudioWorldSource worldSource;

    private volatile boolean closed;

    private AudioPlaybackChannel(final int source, final SoundManager soundManager, final PacketReader packets, final AudioDecoder decoder, final PlaybackClock clock) {
        super(source);
        this.soundManager = soundManager;
        this.packets = packets;
        this.decoder = decoder;
        this.clock = clock;

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
            channel.tryQueueFrames(QUEUE_AT_LEAST_SECONDS, true);
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
        final Vec3 listenerPos = soundManager.getListenerTransform().position();
        linearAttenuation(worldSource.attenuationDistance());
        setSelfPosition(worldSource.resolveSourcePos(listenerPos));
        setRelative(false);
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
            final Vec3 listenerPos = soundManager.getListenerTransform().position();
            setSelfPosition(worldSource.resolveSourcePos(listenerPos));
        }

        final int processedBuffers = removeProcessedBuffers();
        for (int i = 0; i < processedBuffers; i++) {
            lastPlayedFrameEndTime = queuedFrameEndTimes.removeFirst();
        }

        // The clock should always be somewhere within the frame that we know to be currently playing
        final double currentFrameEndTime = Objects.requireNonNullElse(queuedFrameEndTimes.peekFirst(), lastPlayedFrameEndTime);
        clock.ensureInRange(lastPlayedFrameEndTime, currentFrameEndTime);

        tryQueueFrames(lastPlayedFrameEndTime + QUEUE_AT_LEAST_SECONDS, false);

        if (!playing() && !clock.isPaused()) {
            if (!queuedFrameEndTimes.isEmpty()) {
                // Somehow ran out of samples, but we're ready to resume again
                play();
            } else if (!packets.hasRemainingAudio()) {
                scheduleClose();
            }
        }
    }

    private void tryQueueFrames(final double queueUntilTime, final boolean block) {
        try {
            queueFrames(queueUntilTime, block);
        } catch (final DecoderException e) {
            LOGGER.error("Failed to read from audio stream", e);
            scheduleClose();
        }
    }

    private void queueFrames(final double queueUntilTime, final boolean block) throws DecoderException {
        while (queuedFrameEndTimes.isEmpty() || queuedFrameEndTimes.getLast() < queueUntilTime) {
            final AudioFrame frame = decoder.readFrame();
            if (frame != null) {
                queueFrame(frame);
                continue;
            }
            final AudioPacket packet = block ? packets.takeAudioPacket() : packets.pollAudioPacket();
            if (packet == null) {
                break;
            }
            try (packet) {
                decoder.sendPacket(packet);
            }
        }
    }

    private void queueFrame(final AudioFrame frame) throws DecoderException {
        try (frame) {
            final double frameDuration = (double) frame.samples() / decoder.format().getSampleRate();
            final double frameEndTime = frame.presentTime() + frameDuration;
            final ByteBuffer buffer = BufferUtils.createByteBuffer(frame.bytes());
            frame.unpackSamples(buffer);
            new SoundBuffer(buffer.flip(), decoder.format()).releaseAlBuffer().ifPresent(id -> {
                AL10.alSourceQueueBuffers(source, new int[]{id});
                queuedFrameEndTimes.addLast(frameEndTime);
            });
        }
    }

    private int removeProcessedBuffers() {
        final int bufferCount = AL10.alGetSourcei(source, AL11.AL_BUFFERS_PROCESSED);
        if (bufferCount == 0) {
            return 0;
        }
        final int[] buffers = new int[bufferCount];
        AL10.alSourceUnqueueBuffers(source, buffers);
        OpenAlUtil.checkALError("Unqueue buffers");
        AL10.alDeleteBuffers(buffers);
        OpenAlUtil.checkALError("Remove processed buffers");
        return bufferCount;
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
        super.destroy();
        decoder.close();
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

        public boolean isStopped() {
            final AudioPlaybackChannel channel = inner.getNow(null);
            return channel != null && channel.stopped();
        }
    }
}
