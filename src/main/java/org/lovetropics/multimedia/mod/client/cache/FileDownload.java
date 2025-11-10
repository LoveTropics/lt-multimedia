package org.lovetropics.multimedia.mod.client.cache;

import com.mojang.logging.LogUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/* package-private */ class FileDownload implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final OpenOption[] OPEN_OPTIONS = {
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
    };

    private final Path path;
    private final long size;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition canRead = lock.newCondition();

    private final FileChannel file;
    private long writeIndex;

    private boolean writerOpen = true;
    private int readersOpen;

    private final CompletableFuture<Void> completeFuture = new CompletableFuture<>();

    private FileDownload(final Path path, final long size) throws IOException {
        this.path = path;
        this.size = size;
        file = FileChannel.open(path, OPEN_OPTIONS);
    }

    public static HttpResponse.BodyHandler<Response> bodyHandler() {
        return responseInfo -> new Response(responseInfo.headers().firstValueAsLong("Content-Length"));
    }

    private void writeFully(final ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            lock.lock();
            final FileChannel file = Objects.requireNonNull(this.file);
            try {
                file.position(writeIndex);
                final int written = file.write(buffer);
                if (written > 0) {
                    writeIndex += written;
                    canRead.signal();
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private void reportError(final Throwable throwable) {
        LOGGER.error("An error occurred while downloading file", throwable);
        completeFuture.completeExceptionally(throwable);
        closeWriter();
    }

    private void reportComplete() {
        completeFuture.complete(null);
        closeWriter();
    }

    public CompletionStage<Void> awaitComplete() {
        return completeFuture;
    }

    public boolean isComplete() {
        return completeFuture.state() == Future.State.SUCCESS;
    }

    public Path path() {
        return path;
    }

    public long size() {
        return size;
    }

    public SeekableByteChannel openChannel() throws IOException {
        lock.lock();
        try {
            // The FileChannel is already closed or will be soon, just read directly from the file
            if (!writerOpen) {
                return Files.newByteChannel(path);
            }
            readersOpen++;
        } finally {
            lock.unlock();
        }

        return new SeekableByteChannel() {
            private long readIndex;
            private boolean closed;

            @Override
            public int read(final ByteBuffer dst) throws IOException {
                lock.lock();
                try {
                    while (readIndex < size) {
                        final int readBytes = tryRead(dst);
                        if (readBytes == 0) {
                            if (!writerOpen) {
                                return -1;
                            }
                            canRead.await();
                        } else {
                            return readBytes;
                        }
                    }
                } catch (final InterruptedException e) {
                    close();
                    throw new ClosedByInterruptException();
                } finally {
                    lock.unlock();
                }
                return -1;
            }

            private int tryRead(final ByteBuffer dst) throws IOException {
                // Note: if we seek ahead of the writer, we'll just block until we have enough bytes
                final long availableBytes = writeIndex - readIndex;
                if (availableBytes <= 0) {
                    return 0;
                }

                final int oldLimit = dst.limit();
                dst.limit((int) Math.min(oldLimit, dst.position() + availableBytes));
                file.position(readIndex);

                final int readBytes = file.read(dst);
                if (readBytes != -1) {
                    readIndex += readBytes;
                }

                dst.limit(oldLimit);
                return readBytes;
            }

            @Override
            public int write(final ByteBuffer src) throws IOException {
                throw new IOException("Writing is not supported");
            }

            @Override
            public long position() {
                return readIndex;
            }

            @Override
            public SeekableByteChannel position(final long newPosition) {
                readIndex = newPosition;
                return this;
            }

            @Override
            public long size() {
                return size;
            }

            @Override
            public SeekableByteChannel truncate(final long size) throws IOException {
                throw new IOException("Truncation is not supported");
            }

            @Override
            public boolean isOpen() {
                return !closed;
            }

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                closeReader();
            }
        };
    }

    private void closeWriter() {
        lock.lock();
        try {
            writerOpen = false;
            if (readersOpen > 0) {
                canRead.signal();
            } else {
                close();
            }
        } finally {
            lock.unlock();
        }
    }

    private void closeReader() {
        lock.lock();
        try {
            if (--readersOpen == 0) {
                if (!writerOpen) {
                    close();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            IOUtils.closeQuietly(file);
        } finally {
            lock.unlock();
        }
    }

    /* package-private */ static class Response implements HttpResponse.BodySubscriber<Response> {
        private final OptionalLong contentLength;

        private final ReentrantLock lock = new ReentrantLock();
        @Nullable
        private volatile FileDownload download;
        @Nullable
        private Flow.Subscription subscription;

        public Response(final OptionalLong contentLength) {
            this.contentLength = contentLength;
        }

        public FileDownload startWritingTo(final Path path) throws IOException {
            final long contentLength = this.contentLength
                    .orElseThrow(() -> new IOException("Response did not have Content-Length header"));
            final FileDownload download = new FileDownload(path, contentLength);

            lock.lock();
            try {
                if (this.download != null) {
                    throw new IllegalStateException("Already started download");
                }
                this.download = download;
                if (subscription != null) {
                    subscription.request(1);
                }
                return download;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public CompletionStage<Response> getBody() {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void onSubscribe(final Flow.Subscription subscription) {
            lock.lock();
            try {
                if (this.subscription != null) {
                    subscription.cancel();
                    return;
                }
                this.subscription = subscription;
                if (download != null) {
                    subscription.request(1);
                }
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void onNext(final List<ByteBuffer> item) {
            final FileDownload download = this.download;
            if (download == null) {
                return;
            }
            try {
                for (final ByteBuffer buffer : item) {
                    download.writeFully(buffer);
                }
                Objects.requireNonNull(subscription).request(1);
            } catch (final Throwable throwable) {
                download.reportError(throwable);
            }
        }

        @Override
        public void onError(final Throwable throwable) {
            final FileDownload download = this.download;
            if (download != null) {
                download.reportError(throwable);
            }
        }

        @Override
        public void onComplete() {
            final FileDownload download = this.download;
            if (download != null) {
                download.reportComplete();
            }
        }
    }
}
