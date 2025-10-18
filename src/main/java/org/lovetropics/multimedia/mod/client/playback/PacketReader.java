package org.lovetropics.multimedia.mod.client.playback;

import com.mojang.logging.LogUtils;
import org.apache.commons.io.IOUtils;
import org.lovetropics.multimedia.AudioPacket;
import org.lovetropics.multimedia.MultimediaPacket;
import org.lovetropics.multimedia.MultimediaReader;
import org.lovetropics.multimedia.VideoPacket;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/* package-private */ class PacketReader implements AutoCloseable {
    private static final int MAX_QUEUE_CAPACITY = 64;
    private static final int PREFERRED_QUEUE_SIZE = 32;

    private static final Logger LOGGER = LogUtils.getLogger();

    private final MultimediaReader reader;
    private final Thread thread;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition wakeUp = lock.newCondition();

    private final PacketQueue<VideoPacket> videoQueue = new PacketQueue<>(MAX_QUEUE_CAPACITY, PREFERRED_QUEUE_SIZE);
    private final PacketQueue<AudioPacket> audioQueue = new PacketQueue<>(MAX_QUEUE_CAPACITY, PREFERRED_QUEUE_SIZE);

    private volatile boolean sleeping;
    private volatile boolean closed;

    PacketReader(final MultimediaReader reader) {
        this.reader = reader;
        thread = Playback.IO_THREAD_BUILDER.start(this::run);
    }

    public void discardVideo() {
        videoQueue.discard();
    }

    public void discardAudio() {
        audioQueue.discard();
    }

    @Nullable
    public VideoPacket takeVideoPacket() {
        try {
            return videoQueue.take();
        } catch (final InterruptedException e) {
            return null;
        }
    }

    @Nullable
    public AudioPacket takeAudioPacket() {
        try {
            return audioQueue.take();
        } catch (final InterruptedException e) {
            return null;
        }
    }

    @Nullable
    public AudioPacket pollAudioPacket() {
        return audioQueue.poll();
    }

    private void run() {
        try {
            MultimediaPacket packet;
            while (!closed && (packet = reader.readPacket()) != null) {
                enqueueAndSleep(packet);
            }
        } catch (final InterruptedException ignored) {
            // Closed from the main thread, stop immediately
        } catch (final IOException e) {
            LOGGER.error("Failed to read packet from stream", e);
        } finally {
            closed = true;
            lock.lock();
            try {
                videoQueue.signalClosed();
                audioQueue.signalClosed();
            } finally {
                lock.unlock();
            }
            IOUtils.closeQuietly(reader);
        }
    }

    private void enqueueAndSleep(final MultimediaPacket packet) throws InterruptedException {
        switch (packet) {
            case final VideoPacket videoPacket -> enqueueAndSleep(videoQueue, videoPacket);
            case final AudioPacket audioPacket -> enqueueAndSleep(audioQueue, audioPacket);
            default -> packet.close();
        }
    }

    private <P extends MultimediaPacket> void enqueueAndSleep(final PacketQueue<P> queue, final P packet) throws InterruptedException {
        if (queue.discard) {
            packet.close();
            return;
        }
        lock.lock();
        try {
            if (queue.enqueue(packet) && shouldSleep()) {
                sleeping = true;
                wakeUp.await();
                sleeping = false;
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean shouldSleep() {
        if (!videoQueue.wantsPacket() && !audioQueue.wantsPacket()) {
            return true;
        }
        // To avoid stalling on either queue, ignore maximum capacity if the other queue is totally empty
        if (videoQueue.needsPacket() || audioQueue.needsPacket()) {
            return false;
        } else {
            return !(videoQueue.canAcceptPacket() && audioQueue.canAcceptPacket());
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        thread.interrupt();
        lock.lock();
        try {
            videoQueue.discard();
            audioQueue.discard();
        } finally {
            lock.unlock();
        }
    }

    private class PacketQueue<P extends MultimediaPacket> {
        private final int capacity;
        private final int preferredSize;

        private final Queue<P> queue;
        private final Condition hasPacket = lock.newCondition();
        private volatile boolean discard;

        private PacketQueue(final int capacity, final int preferredSize) {
            this.capacity = capacity;
            this.preferredSize = preferredSize;
            queue = new ArrayDeque<>(capacity);
        }

        public boolean needsPacket() {
            return !discard && queue.isEmpty();
        }

        public boolean wantsPacket() {
            return !discard && queue.size() < preferredSize;
        }

        public boolean canAcceptPacket() {
            return discard || queue.size() < capacity;
        }

        @Nullable
        public P poll() {
            if (discard) {
                return null;
            }
            lock.lock();
            try {
                return queue.poll();
            } finally {
                lock.unlock();
            }
        }

        @Nullable
        public P take() throws InterruptedException {
            if (discard) {
                return null;
            }
            lock.lock();
            try {
                while (!discard) {
                    final P packet = queue.poll();
                    if (sleeping && !shouldSleep()) {
                        wakeUp.signal();
                    }
                    if (packet != null || closed) {
                        return packet;
                    }
                    hasPacket.await();
                }
            } finally {
                lock.unlock();
            }
            return null;
        }

        public boolean enqueue(final P packet) {
            if (discard) {
                return false;
            }
            lock.lock();
            try {
                queue.add(packet);
                hasPacket.signal();
                return true;
            } finally {
                lock.unlock();
            }
        }

        public void discard() {
            lock.lock();
            try {
                discard = true;
                MultimediaPacket packet;
                while ((packet = queue.poll()) != null) {
                    packet.close();
                }
                hasPacket.signal();
            } finally {
                lock.unlock();
            }
        }

        public void signalClosed() {
            lock.lock();
            try {
                hasPacket.signal();
            } finally {
                lock.unlock();
            }
        }
    }
}
