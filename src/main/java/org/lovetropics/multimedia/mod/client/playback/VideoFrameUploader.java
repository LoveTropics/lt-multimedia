package org.lovetropics.multimedia.mod.client.playback;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import org.lovetropics.multimedia.DecoderException;
import org.lovetropics.multimedia.VideoFrame;
import org.lovetropics.multimedia.mod.client.GpuExtensions;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class VideoFrameUploader implements AutoCloseable {
    private static final int BUFFER_COUNT = 3;

    private final GpuDevice device;
    private FrameSize requestedFrameSize;
    private final ClockSyncer clock;

    private final FrameBuffer[] frameBuffers = new FrameBuffer[BUFFER_COUNT];
    private int nextWriteIndex;
    private int nextReadIndex;

    private final ReentrantLock writeFrameLock = new ReentrantLock();
    private final Condition canWrite = writeFrameLock.newCondition();

    private boolean seeking;

    public VideoFrameUploader(final GpuDevice device, final FrameSize frameSize, final ClockSyncer clock) {
        this.device = device;
        requestedFrameSize = frameSize;
        for (int i = 0; i < frameBuffers.length; i++) {
            frameBuffers[i] = new FrameBuffer(device, frameSize);
        }
        this.clock = clock;
    }

    public void beginSeek() {
        writeFrameLock.lock();
        try {
            if (seeking) {
                return;
            }
            seeking = true;
            nextWriteIndex = 0;
            nextReadIndex = 0;
            for (final FrameBuffer buffer : frameBuffers) {
                buffer.discardAndFreeze(device, requestedFrameSize);
            }
        } finally {
            writeFrameLock.unlock();
        }
    }

    public void endSeek() {
        writeFrameLock.lock();
        try {
            if (!seeking) {
                throw new IllegalStateException("Not seeking");
            }
            seeking = false;
            for (final FrameBuffer buffer : frameBuffers) {
                buffer.unfreeze();
            }
        } finally {
            writeFrameLock.unlock();
        }
    }

    public void tick() {
        boolean readyForWrite = false;
        for (final FrameBuffer buffer : frameBuffers) {
            buffer.tick(device, requestedFrameSize);
            readyForWrite |= buffer.isReadyForWrite();
        }
        discardExpiredFrames();

        if (readyForWrite) {
            writeFrameLock.lock();
            try {
                canWrite.signal();
            } finally {
                writeFrameLock.unlock();
            }
        }
    }

    // If we stop rendering (for example the playback goes off-screen), we don't want to stall it due to frames not being consumed
    private void discardExpiredFrames() {
        for (int i = 0; i < frameBuffers.length - 1; i++) {
            final int index = (nextReadIndex + i) % frameBuffers.length;
            final int nextIndex = (index + 1) % frameBuffers.length;
            final FrameBuffer nextFrameBuffer = frameBuffers[nextIndex];
            if (nextFrameBuffer.isReadyToPresent() && clock.getElapsedTime() >= nextFrameBuffer.presentTime) {
                // The next frame is already ready, we will never need to present this one
                frameBuffers[index].recycle(device, requestedFrameSize);
                nextReadIndex = nextIndex;
            } else {
                break;
            }
        }
    }

    public void writeFrame(final VideoFrame frame) throws DecoderException, InterruptedException {
        // This frame is already too late, skip it!
        if (clock.getElapsedTime() > frame.presentEndTime()) {
            frame.close();
            return;
        }
        writeFrameLock.lock();
        try (frame) {
            if (seeking) {
                return;
            }
            while (!frameBuffers[nextWriteIndex].tryWriteFrom(frame)) {
                canWrite.await();
                if (seeking) {
                    return;
                }
            }
            nextWriteIndex = (nextWriteIndex + 1) % frameBuffers.length;
        } finally {
            writeFrameLock.unlock();
        }
    }

    @Nullable
    public PresentableVideoFrame takeNextFrame() {
        if (seeking) {
            return null;
        }

        final FrameBuffer buffer = frameBuffers[nextReadIndex];
        if (!buffer.isReadyToPresent() || clock.getElapsedTime() < buffer.presentTime) {
            return null;
        }

        return new PresentableVideoFrame() {
            private boolean closed;

            private void checkValid() {
                if (closed) {
                    throw new IllegalStateException("Frame has already been closed");
                }
            }

            @Override
            public void copyTo(final GpuTexture texture) {
                checkValid();
                buffer.copyTo(device, texture);
            }

            @Override
            public FrameSize frameSize() {
                checkValid();
                return buffer.frameSize;
            }

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                buffer.recycle(device, requestedFrameSize);
                nextReadIndex = (nextReadIndex + 1) % frameBuffers.length;
            }
        };
    }

    public void requestFrameSize(final FrameSize frameSize) {
        requestedFrameSize = frameSize;
    }

    @Override
    public void close() {
        for (final FrameBuffer buffer : frameBuffers) {
            buffer.close();
        }
    }

    private static class FrameBuffer implements AutoCloseable {
        private enum State {
            // Render Thread -> Decoder Thread
            READY_FOR_WRITE,
            // Decoder Thread -> Decoder Thread
            WRITE_BEGIN,
            // Decoder Thread -> Render Thread
            WRITE_END,
            // Render Thread
            READY_TO_PRESENT,
            // Render Thread
            RECYCLING,
            // Render Thread
            FROZEN,
            // Render Thread
            CLOSED,
        }

        private GpuBuffer buffer;
        private volatile FrameSize frameSize;

        @Nullable
        private volatile GpuBuffer.MappedView mappedView;
        private volatile double presentTime;

        private final AtomicReference<State> state = new AtomicReference<>(State.READY_FOR_WRITE);

        @Nullable
        private GpuFence recycleFence;

        private FrameBuffer(final GpuDevice device, final FrameSize frameSize) {
            this.frameSize = frameSize;
            buffer = createBuffer(device, frameSize);
            mapBuffer(device, frameSize);
        }

        private boolean tryUpdateState(final State fromState, final State toState) {
            return state.compareAndSet(fromState, toState);
        }

        private void updateState(final State fromState, final State toState) {
            final State oldState = state.compareAndExchange(fromState, toState);
            if (oldState != fromState) {
                throw new IllegalStateException("Expected state " + fromState + ", but was " + oldState);
            }
        }

        private void checkState(final State expectedState) {
            final State state = this.state.get();
            if (state != expectedState) {
                throw new IllegalStateException("Expected state " + expectedState + ", but was " + state);
            }
        }

        private GpuBuffer createBuffer(final GpuDevice device, final FrameSize frameSize) {
            final int sizeBytes = frameSize.width() * frameSize.height() * TextureFormat.RGBA8.pixelSize();
            return device.createBuffer(() -> "Video frame buffer", GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_MAP_WRITE, sizeBytes);
        }

        private void mapBuffer(final GpuDevice device, final FrameSize requestedFrameSize) {
            if (mappedView != null) {
                throw new IllegalStateException("Buffer is already mapped");
            }
            if (!frameSize.equals(requestedFrameSize)) {
                buffer.close();
                buffer = createBuffer(device, requestedFrameSize);
                frameSize = requestedFrameSize;
            }
            mappedView = device.createCommandEncoder().mapBuffer(buffer, false, true);
        }

        public void tick(final GpuDevice device, final FrameSize requestedFrameSize) {
            if (tryUpdateState(State.WRITE_END, State.READY_TO_PRESENT)) {
                Objects.requireNonNull(mappedView).close();
                mappedView = null;
            } else {
                tickRecycle(device, requestedFrameSize);
            }
        }

        private void tickRecycle(final GpuDevice device, final FrameSize requestedFrameSize) {
            if (state.get() != State.RECYCLING) {
                return;
            }
            if (recycleFence == null || recycleFence.awaitCompletion(0)) {
                if (recycleFence != null) {
                    recycleFence.close();
                    recycleFence = null;
                }
                // Texture copy has completed, we can safely map the buffer and start writing into it again
                mapBuffer(device, requestedFrameSize);
                updateState(State.RECYCLING, State.READY_FOR_WRITE);
            }
        }

        public boolean tryWriteFrom(final VideoFrame frame) throws DecoderException {
            if (!tryUpdateState(State.READY_FOR_WRITE, State.WRITE_BEGIN)) {
                return false;
            }
            final GpuBuffer.MappedView mappedView = Objects.requireNonNull(this.mappedView);
            presentTime = frame.presentTime();
            frame.unpackPixels(frameSize.width(), frameSize.height(), mappedView.data());
            mappedView.data().flip();
            updateState(State.WRITE_BEGIN, State.WRITE_END);
            return true;
        }

        public boolean isReadyForWrite() {
            return state.get() == State.READY_FOR_WRITE;
        }

        public boolean isReadyToPresent() {
            return state.get() == State.READY_TO_PRESENT;
        }

        public void copyTo(final GpuDevice device, final GpuTexture texture) {
            checkState(State.READY_TO_PRESENT);
            GpuExtensions.copyBufferToTexture(buffer, texture, 0, 0, frameSize.width(), frameSize.height(), NativeImage.Format.RGBA);
            if (recycleFence != null) {
                recycleFence.close();
            }
            recycleFence = device.createCommandEncoder().createFence();
        }

        public void recycle(final GpuDevice device, final FrameSize requestedFrameSize) {
            updateState(State.READY_TO_PRESENT, State.RECYCLING);
            // If we are able to immediately recycle this buffer, we may as well not wait
            tickRecycle(device, requestedFrameSize);
        }

        public void discardAndFreeze(final GpuDevice device, final FrameSize requestedFrameSize) {
            final State state = this.state.get();
            if (state == State.CLOSED) {
                return;
            } else if (state == State.WRITE_BEGIN) {
                throw new IllegalStateException("Cannot discard while write is in progress");
            }

            if (recycleFence != null) {
                recycleFence.awaitCompletion(Long.MAX_VALUE);
                recycleFence = null;
            }
            if (mappedView == null) {
                mapBuffer(device, requestedFrameSize);
            }
            updateState(state, State.FROZEN);
        }

        public void unfreeze() {
            updateState(State.FROZEN, State.READY_FOR_WRITE);
        }

        @Override
        public void close() {
            State state = this.state.get();
            if (state == State.CLOSED) {
                return;
            }

            // A write is currently in progress on another thread, spin until it completes
            while (state == State.WRITE_BEGIN || !this.state.compareAndSet(state, State.CLOSED)) {
                Thread.yield();
                state = this.state.get();
            }

            final GpuBuffer.MappedView mappedView = this.mappedView;
            this.mappedView = null;
            if (mappedView != null) {
                mappedView.close();
            }
            if (recycleFence != null) {
                recycleFence.close();
                recycleFence = null;
            }
            buffer.close();
        }
    }
}
